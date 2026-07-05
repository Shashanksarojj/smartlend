package com.smartlend.loan.model;

public enum DocumentType {
    INCOME_PROOF,      // salary slips, P&L statement
    IDENTITY_PROOF,    // PAN card, Aadhaar, passport
    ADDRESS_PROOF,     // utility bill, rental agreement
    BANK_STATEMENT,    // last 6 months statements
    EMPLOYMENT_LETTER, // offer letter, appointment letter
    OTHER
}
