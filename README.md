# kotoba-fleet-vcs

west 後継の AI-agent フリート向け VCS レイヤ（ADR-2607160005）。この repo は
**Phase 0 — 読み取りモデル**: `manifest/west.yml`（生成物）を fleet-db（EDN の
repo entity 集合 + append-only ledger）に import し、byte-exact な projection
round-trip と、pin SHA 直接 fetch による**並列 sync** を提供する。

west 体制の正本はまだ変えない（Phase 0 の設計制約）。pin の前進は
`fleet pin-advance`（ledger event + db 更新）→ 既存の検証済み経路
`nbb scripts/gen-west-manifest.cljs --entry <name>` への委譲、の 2 相。

## Runtime

第一の実行経路は **nbb**（repo 運用 tooling の正、CLAUDE.md 準拠）。core は
pure `.cljc`（`fleet.west` / `fleet.db` / `fleet.sync`）で、IO は
`bin/fleet.cljs` に隔離（pure planner + injected runner、`kotoba/git_adapter.cljc`
と同じ流儀）。

## Usage

```bash
# import: west.yml -> fleet-db
nbb --classpath src bin/fleet.cljs import --west ../../manifest/west.yml --out fleet-db.edn

# check: fleet-db の projection が west.yml と byte 一致するか（Phase 0 受け入れ基準）
nbb --classpath src bin/fleet.cljs check --west ../../manifest/west.yml --db fleet-db.edn

# 並列 sync: working set を pin で workspace に実体化（dirty は skip、west 意味論）
nbb --classpath src bin/fleet.cljs sync --db fleet-db.edn --workspace /tmp/ws \
    --names kotoba,kotobase --jobs 8

# クエリ / 統計
nbb --classpath src bin/fleet.cljs stats --db fleet-db.edn
nbb --classpath src bin/fleet.cljs list --db fleet-db.edn --org kotoba-lang

# pin 前進（2 相: ledger + db 更新 → 既存 --entry 経路へ委譲）
nbb --classpath src bin/fleet.cljs pin-advance --db fleet-db.edn --repo kotoba --new <sha>
```

## Tests

（agent 提案の実例: この節見出し直下の一行は governed land-back の dogfood で追加された）


```bash
nbb --classpath src:test run-tests.cljs
```

## Design invariants (Phase 0)

- `west/parse` → `west/emit` は generator 方言上の**全単射**（byte-exact）。
- sync planner は **pin SHA を直接 fetch** する（`fetch --depth 1 origin <sha>`）
  — tip との関係を推測しないため、shallow×pin の不整合クラスと local ancestry
  誤判定クラスがこの層に存在しない。
- dirty checkout は **skip し、決して上書きしない**（west 意味論の維持）。
- ledger は append-only（1 行 1 EDN map、monotonic `:event/seq`、
  canvas-ledger.edn と同型）。admission gate（署名・単調 sequence・上流到達性）
  は Phase 1。

## Phase 2 — identity + governed land-back

- `fleet keygen` は did:key も出力（`fleet.did`、base58btc/multicodec）。
- `fleet grant` — owner root から agent への委譲鎖（attenuation・expiry・
  linkage を `fleet.grant/verify-chain` が検証。cacao-clj と同意味論、
  CAIP-122 wire format 化は follow-up）。
- `fleet propose` — grant 保持を検証して land 提案を ledger に記録。
- `fleet govern` — quorum pre-check（不足なら merge せず abort）→ サーバ側
  マージ → k-of-n Governor 署名の canonical pin advance（`admit-quorum`:
  quorum / sequence / parent / reachability / value-advance）。
- 残: workspace manager（GC / checkpoint / best-of-N）と鍵の 1Password 移設。

## Phase ⑥ — per-agent identity (hard-flip prerequisite)

- `fleet enroll --agent NAME --grant PATH --registry fleet-agents.edn` —
  各 agent セッションに専用 did:key を mint し kagi に格納、append-only の
  agent registry に scoped grant 付きで登録。共有 owner 鍵でなく**自分の鍵**で
  署名する。governance keyring（fleet-keys.edn、roots/canonical）は人間が管理、
  registry は機械管理（base-datoms/ledger 分離と同型）。
- `--registry` を渡すと pin-advance が registry の grant を keyring に merge
  （governance が衝突時に勝つ）。GitHub 側は全 agent が同一 owner token で
  push するため per-agent 区別は原理的に不可 — 署名レイヤの per-agent auth が
  hard flip の前提。実 flip（legacy 書き込み拒否）は全 session enroll 後。

## Phase 3b — fleet head gossip (p2p)

- `fleet announce --head fleet-head.edn` — P3a signed head を
  kotoba-lang/p2p ワイヤ互換の head-announce メッセージ（`{:type
  :head-announce :graph "fleet-db" :head-cid :seq :fleet-head}`）に。
- `fleet receive --msg announce.edn --keys fleet-keys.edn --state s.edn`
  — trust set（keyring roots + canonical allow）で署名検証し、seq が前進
  する時だけ adopt（monotonic、pin と同規則）。改竄・untrusted signer・
  downgrade は拒否。フリート機は GitHub を polling せず互いの fleet head を
  この gossip で学ぶ（GitHub は mirror に降格）。block-chasing（kotoba-git
  object graph 転送）は後続スライス。

## Phase 1.5 + 3a — flip staging & signed fleet head

- `fleet reconcile [--check]` — legacy 経路（gen --entry / API single-entry）の
  west.yml 書き込みを fleet-db に **attributed ledger events** として吸収。
  CI（superproject の `fleet-projection-verify.yml`）が main 上の drift を
  自動吸収し、fleet-db は lossless に保たれる（hard flip までの dual-write 吸収）。
- `fleet head [--verify]` — fleet-db 内容全体への **自己証明 signed head**
  （sha256 content hash + monotonic sequence + parent-covering、ipns.head 型）。
  これが Phase 3 の p2p head-announce（kotoba-lang/p2p、E2E 検証済み）が
  フリート機間で運ぶ record になる。P3b（実 p2p 配線・複数機 seeding）は
  JVM 側 follow-up。

## Naming

`kotoba-lang/kotoba-fleet`（lease + governor-drain + fleet-view の agent
並行実行コーディネーション基盤、ADR-2606302000）とは**別レイヤの兄弟 repo**。
あちらは「複数 agent の実行をぶつけない」係、この repo は「repo 群の
manifest / pin / sync（west 後継 VCS プレーン）」係。短名が取られているため
role suffix `-vcs` を付けた（repo naming 規約 ADR-2607102200 addendum 14）。

## Roadmap

ADR-2607160005 の Phase 1（signed pins / admission gate）→ Phase 2（agent
identity + governed land-back、既存 kotoba-fleet の governor との統合検討）
→ Phase 3（kotoba-git object plane + p2p）。
