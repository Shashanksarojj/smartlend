from dataclasses import dataclass, field
from typing import Optional


@dataclass
class ScoringRequest:
    monthly_income: float
    requested_amount: float
    tenure_months: int
    employment_type: str
    existing_loans: int = 0


EMPLOYMENT_MULTIPLIER = {
    "SALARIED":      1.0,
    "SELF_EMPLOYED": 0.85,
    "BUSINESS":      0.90,
}


def compute_credit_score(req: ScoringRequest) -> int:
    score = 500

    annual_income = req.monthly_income * 12
    dti = req.requested_amount / annual_income if annual_income > 0 else 999

    if dti < 1:
        score += 200
    elif dti < 2:
        score += 130
    elif dti < 3:
        score += 60
    elif dti < 5:
        score -= 20
    else:
        score -= 150

    if req.tenure_months <= 12:
        score += 80
    elif req.tenure_months <= 36:
        score += 40
    elif req.tenure_months > 60:
        score -= 50

    multiplier = EMPLOYMENT_MULTIPLIER.get(req.employment_type, 0.8)
    score = int(score * multiplier)

    score -= req.existing_loans * 30

    if req.monthly_income >= 100000:
        score += 60
    elif req.monthly_income >= 50000:
        score += 30
    elif req.monthly_income >= 25000:
        score += 10

    return max(300, min(900, score))


def get_risk_and_rate(score: int) -> tuple[str, float]:
    if score >= 750:
        return "LOW", 10.5
    elif score >= 650:
        return "MEDIUM", 13.5
    elif score >= 550:
        return "MEDIUM", 16.0
    else:
        return "HIGH", 20.0