# Insurance Premium — Pricing Policy

This policy explains the business reasoning behind the `insurance-premium` decision model.

## Why age and years-licensed are both checked

Driver age alone is a weaker predictor of claim risk than it used to be, because a 40-year-old who
just got their license has little road experience despite their age. Years licensed captures actual
driving experience directly, so the model checks it as an independent condition rather than folding
it into an age band — either a young driver or a newly licensed driver (of any age) lands in the
`HIGH_RISK` tier.

## Why vehicle value matters independently of the driver

A high-value vehicle costs more to repair or replace after an at-fault claim regardless of how safe
the driver is, so the model prices that risk separately from driver-based risk factors rather than
trying to combine them into a single score.
