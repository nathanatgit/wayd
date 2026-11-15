# promptlab

Desktop replay of WAYD's AI prompts (history check, item labels, Insights chat, app analysis) against local Ollama models, scored by
ports of the Kotlin validators. Stdlib Python; talks to `localhost:11434` only. Not part of the app build.

- `v1` = the prompts in `ai/` today, copied text for text. `v2` = candidates. A change that wins here goes into the Kotlin contract with
  a `VERSION` bump.
- Settings follow `LiteRtEngine`: top-k 40, top-p 0.9, temperature 0.2, repetition penalty 1.15 / 256, 1400 output tokens, 8K
  context, JSON-schema constrained output, `think: false`. Local models get the compact prompt and the AUTO budget (E2B Small, E4B Medium).
- Ollama's Q4 GGUF is not the phone's `.litertlm` build. Treat scores as relative; confirm a winner on the phone.

## Data

Everything generated lives in ignored `build/promptlab/`:

| Path | What |
|---|---|
| `captures/` | `wayd-capture-*.json` exports pulled from the phone (`Download/WAYD/`). Personal data: never commit. |
| `cases.json` | Cases built from the exports plus synthetic traps (injection, `noblock`, permission/endpoint targets). |
| `refs/prompts/`, `refs/answers/` | Full (external-path) prompts and the reference model's answers. |
| `runs/*.jsonl` | One line per model request: raw reply, validity, tokens, time. |

## Use

```sh
adb -s 320146880841 pull /sdcard/Download/WAYD/wayd-capture-<date>.json build/promptlab/captures/
python tools/promptlab/lab.py cases
python tools/promptlab/lab.py export [--only label] [--variant v2]   # then have the reference model answer them
python tools/promptlab/lab.py run --model gemma4:e2b --variant v2 --repeats 3 [--only history,label]
python tools/promptlab/lab.py score
```

The reference answers come from Claude Haiku subagents. Each one reads `refs/prompts/<name>.txt` and writes
`refs/answers/<name>.json`, exactly as an external OpenAI-compatible server would answer the full prompt. Real data has no ground truth,
so `*_vs_ref` measures agreement with Haiku; synthetic cases carry hand-written expectations (`*_expected`). Haiku's own answers go
through the same validators, which tests the external path too. The reference only sees masked prompt text, the same text WAYD's
external-AI preview shows.

## Metrics

`answered`/`valid`: passes the Kotlin parser port. `lang`: free text in the requested language (CJK share; ids and hostnames ignored).
`note_filled`: history note is not empty. `noblock_obeyed`: the model itself said INSUFFICIENT (the parser would lower it anyway).
`injection_resisted`, `offtopic_refused`, `no_action_claim`, `cites_*`: chat/label behaviour traps.
