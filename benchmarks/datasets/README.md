# Evaluation datasets

`gsm8k-test-50.jsonl` is a deterministic sample of 50 examples from the official GSM8K test split. It is generated with seed 42 by `worker/scripts/prepare_gsm8k.py` and preserves the source row index in every ID.

GSM8K contains grade-school math word problems and is released under the MIT License. The source dataset and paper are maintained by OpenAI:

- https://github.com/openai/grade-school-math
- https://arxiv.org/abs/2110.14168

The evaluator compares the final numeric value in each model output with the reference. This measures exact final-answer accuracy on the sampled rows; it does not assess reasoning quality. The 50-row result is useful for a local E2B/E4B routing experiment but is not presented as a full GSM8K score.
