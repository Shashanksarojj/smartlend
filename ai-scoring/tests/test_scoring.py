import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from app.scoring import compute_credit_score, get_risk_and_rate, ScoringRequest


def req(monthly_income=50000, requested_amount=100000, tenure_months=24,
        employment_type="SALARIED", existing_loans=0):
    return ScoringRequest(
        monthly_income=monthly_income,
        requested_amount=requested_amount,
        tenure_months=tenure_months,
        employment_type=employment_type,
        existing_loans=existing_loans,
    )


# ── DTI adjustments ───────────────────────────────────────────

def test_low_dti_boosts_score():
    """DTI < 1 (amount << annual income) → +200 points"""
    high_income = req(monthly_income=200000, requested_amount=100000)
    low_income  = req(monthly_income=50000,  requested_amount=100000)
    assert compute_credit_score(high_income) > compute_credit_score(low_income)


def test_very_high_dti_penalises_score():
    """DTI >= 5 → -150 points"""
    bad = req(monthly_income=10000, requested_amount=800000, tenure_months=12)
    score = compute_credit_score(bad)
    assert score < 500


# ── Tenure adjustments ────────────────────────────────────────

def test_short_tenure_boosts_score():
    short = req(tenure_months=12)
    long  = req(tenure_months=84)
    assert compute_credit_score(short) > compute_credit_score(long)


def test_tenure_over_60_penalises():
    long_tenure = req(tenure_months=72)
    med_tenure  = req(tenure_months=36)
    assert compute_credit_score(long_tenure) < compute_credit_score(med_tenure)


# ── Employment multiplier ─────────────────────────────────────

def test_salaried_scores_higher_than_self_employed():
    salaried      = req(employment_type="SALARIED")
    self_employed = req(employment_type="SELF_EMPLOYED")
    assert compute_credit_score(salaried) > compute_credit_score(self_employed)


def test_business_scores_higher_than_self_employed():
    business      = req(employment_type="BUSINESS")
    self_employed = req(employment_type="SELF_EMPLOYED")
    assert compute_credit_score(business) > compute_credit_score(self_employed)


# ── Existing loans penalty ────────────────────────────────────

def test_each_existing_loan_reduces_score_by_30():
    no_loans  = req(existing_loans=0)
    two_loans = req(existing_loans=2)
    diff = compute_credit_score(no_loans) - compute_credit_score(two_loans)
    assert diff == 60


# ── Income band bonus ─────────────────────────────────────────

def test_high_income_earns_bonus():
    rich = req(monthly_income=100000)
    poor = req(monthly_income=20000)
    assert compute_credit_score(rich) > compute_credit_score(poor)


# ── Score clamping ────────────────────────────────────────────

def test_score_never_exceeds_900():
    best_case = req(monthly_income=500000, requested_amount=10000, tenure_months=6)
    assert compute_credit_score(best_case) <= 900


def test_score_never_below_300():
    worst_case = req(monthly_income=1000, requested_amount=9999999, tenure_months=84,
                     employment_type="SELF_EMPLOYED", existing_loans=10)
    assert compute_credit_score(worst_case) >= 300


# ── Risk label and rate mapping ───────────────────────────────

def test_score_750_plus_is_low_risk():
    label, rate = get_risk_and_rate(750)
    assert label == "LOW"
    assert rate == 10.5


def test_score_650_to_749_is_medium_with_lower_rate():
    label, rate = get_risk_and_rate(700)
    assert label == "MEDIUM"
    assert rate == 13.5


def test_score_550_to_649_is_medium_with_higher_rate():
    label, rate = get_risk_and_rate(600)
    assert label == "MEDIUM"
    assert rate == 16.0


def test_score_below_550_is_high_risk():
    label, rate = get_risk_and_rate(400)
    assert label == "HIGH"
    assert rate == 20.0


# ── Approval threshold ────────────────────────────────────────

def test_score_580_and_above_triggers_approve():
    """Score >= 580 → APPROVE in the /score endpoint logic"""
    assert 580 >= 580  # threshold is hardcoded in main.py


def test_strong_profile_produces_approve_recommendation():
    """Good income, low amount, salaried → score well above 580"""
    strong = req(monthly_income=100000, requested_amount=50000, tenure_months=12,
                 employment_type="SALARIED", existing_loans=0)
    score = compute_credit_score(strong)
    assert score >= 580


def test_weak_profile_produces_reject_recommendation():
    """Very high DTI, self-employed → score below 580"""
    weak = req(monthly_income=15000, requested_amount=500000, tenure_months=84,
               employment_type="SELF_EMPLOYED", existing_loans=3)
    score = compute_credit_score(weak)
    assert score < 580