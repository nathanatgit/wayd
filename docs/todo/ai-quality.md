# To-do · AI quality (P1)

The contracts are tested for format, budgets and safety. Nothing yet measures whether Gemma's answers are right. Details of the features: [06-AI-GEMMA.md](../06-AI-GEMMA.md).

## Tasks

| # | Task | Notes |
|---|---|---|
| 1 | Run `HistoryEvalTest` against Gemma 4 E2B and E4B through a local server; record role and advice scores in the handoff. | `WAYD_OLLAMA_URL`, `WAYD_OLLAMA_MODEL`, `WAYD_EVAL_SIZE`. The server stands in for the phone; the prompt is the compact on-device one. |
| 2 | Tune the compact history prompt from the misses (which roles are confused, whether `noblock` is respected). | Change one thing at a time; keep the score before and after in the commit message. |
| 3 | Same kind of opt-in score for per-item labels (`LabelContract`): 8–10 fixtures with expected role, expected links present/absent, expected advice. | Reuse the fixture style of `HistoryEvalTest`. |
| 4 | Chat: fixtures with a report and questions whose answer must cite specific ids; one off-topic question that must be declined. | Score = cited-the-right-row rate. |
| 5 | Prompt-injection fixtures on the real model: hostile text in a hostname, an APK string, chat history and an image caption. | Pass = output structure unchanged, no instruction followed. Roadmap M6 acceptance. |
| 6 | Time on the phone per request type on E2B and E4B (label, history group of 8, chat with and without one image). | Decides whether Small can grow or must shrink. |
| 7 | Stray `OTHER` data type in app analysis (older open item). | |

## Ideas, not scheduled

- Show the model's agreement rate with the rule role as a hint of when the AI adds nothing.
- Cache whole-history results per host across windows instead of per run.
- Let the user mark an AI role as wrong; use it only as a display override, never as evidence.
