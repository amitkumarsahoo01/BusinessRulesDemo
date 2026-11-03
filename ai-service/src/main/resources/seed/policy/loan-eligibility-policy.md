# Loan Eligibility — Underwriting Policy

This policy explains the business reasoning behind the `loan-eligibility` decision model. It is
background for the "why" behind rules, not a restatement of the rules themselves — read the
decision tables for the exact conditions.

## Why we compute a debt ratio first

`Debt Ratio` divides the monthly loan payment by the applicant's monthly income. It is the single
strongest predictor of repayment difficulty we have, so every other decision in the model is
conditioned on it alongside credit score, never on credit score alone. A high income can offset a
mediocre credit score only up to a point — once the monthly payment itself is too large a share of
income, risk goes up regardless of history.

## Why risk category uses a UNIQUE hit policy

`Risk Category` must produce exactly one answer for any given applicant — it would be a modeling
bug if two risk tiers could both apply to the same inputs, since downstream pricing and eligibility
decisions assume a single risk tier. That's why the table is authored with non-overlapping credit
score and debt ratio bands and validated with the UNIQUE hit policy, which the DMN engine will
flag as an error if a future edit ever introduces an overlap.

## Why applicants under 21 always get manual review

Credit history length is itself a risk signal that a raw credit score doesn't fully capture for very
young applicants — someone with a short credit history can show a high score simply from having had
too few opportunities to default. Rather than trying to model this precisely, underwriting policy
requires a human to review every application from an applicant under 21, regardless of what the
risk-category calculation says. This is why that rule is checked first in the `Eligibility` table
(FIRST hit policy) — it must win over every other rule, not just coexist with them.

## Why the 1.1.0 threshold tightening happened

Version 1.1.0 raised the credit-score cutoffs used by `Risk Category` (the "low risk" bar from 700
to 720, and the "fair credit" floor from 620 to 640) after a portfolio review showed the previous
thresholds were approving more medium-risk applicants than the target default rate allowed. This is
a policy tightening, not a bug fix — applicants who would have been LOW risk under 1.0.0 may now be
MEDIUM risk under 1.1.0, which is expected and intended.
