import logging
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional
import numpy as np

from app.scoring import compute_credit_score, get_risk_and_rate

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [ai-scoring] [%(request_id)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)

class _ReqIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "request_id"):
            record.request_id = "-"
        return True

logging.getLogger().addFilter(_ReqIdFilter())
logger = logging.getLogger(__name__)

app = FastAPI(title="SmartLend AI Scoring Service", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def request_logging_middleware(request: Request, call_next):
    request_id = uuid.uuid4().hex[:8]
    request.state.request_id = request_id

    _filter = _ReqIdFilter()

    class _Ctx:
        def filter(self, record):
            record.request_id = request_id
            return True

    ctx = _Ctx()
    logging.getLogger().addFilter(ctx)
    start = time.time()
    try:
        response = await call_next(request)
    finally:
        ms = (time.time() - start) * 1000
        logger.info("%s %s → %s (%.1fms)", request.method, request.url.path, response.status_code, ms)
        logging.getLogger().removeFilter(ctx)

    response.headers["X-Request-Id"] = request_id
    return response


# ── Request / Response Models ─────────────────────────────────

class ScoringRequest(BaseModel):
    monthly_income: float
    requested_amount: float
    tenure_months: int
    employment_type: str           # SALARIED, SELF_EMPLOYED, BUSINESS
    existing_loans: Optional[int] = 0


class ScoringResponse(BaseModel):
    credit_score: int              # 300–900
    risk_label: str                # LOW, MEDIUM, HIGH
    suggested_rate: float          # annual interest rate %
    recommendation: str            # APPROVE or REJECT
    reasoning: str


# ── Endpoints ─────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "UP", "service": "ai-scoring"}


@app.post("/score", response_model=ScoringResponse)
def score_applicant(req: ScoringRequest):
    logger.info(
        "Scoring request — income=%.0f amount=%.0f tenure=%dmo employment=%s",
        req.monthly_income, req.requested_amount, req.tenure_months, req.employment_type,
    )

    credit_score = compute_credit_score(req)
    risk_label, suggested_rate = get_risk_and_rate(credit_score)
    recommendation = "APPROVE" if credit_score >= 580 else "REJECT"

    dti = req.requested_amount / (req.monthly_income * 12) if req.monthly_income > 0 else 0
    reasoning = (
        f"Score {credit_score} based on: "
        f"DTI ratio {dti:.2f}, "
        f"tenure {req.tenure_months}mo, "
        f"employment {req.employment_type}."
    )

    logger.info(
        "Scoring result — score=%d risk=%s rate=%.1f%% recommendation=%s",
        credit_score, risk_label, suggested_rate, recommendation,
    )

    return ScoringResponse(
        credit_score=credit_score,
        risk_label=risk_label,
        suggested_rate=suggested_rate,
        recommendation=recommendation,
        reasoning=reasoning,
    )
