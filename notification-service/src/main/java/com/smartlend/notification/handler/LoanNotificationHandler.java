package com.smartlend.notification.handler;

import com.smartlend.notification.channel.NotificationDispatcher;
import com.smartlend.notification.channel.NotificationPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Translates raw loan event maps into typed NotificationPayloads and hands
 * them to the dispatcher. All HTML templates live here — channels are kept thin.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoanNotificationHandler {

    private final NotificationDispatcher dispatcher;

    public void handleUserRegistered(Map<String, Object> event) {
        String userEmail = str(event, "userEmail");
        String userPhone = str(event, "userPhone");
        String userName  = str(event, "userName", "Valued Customer");

        String subject = "Welcome to SmartLend!";

        String plain = String.format("""
                Welcome, %s!

                Your SmartLend account has been created successfully.

                You can now log in and apply for a loan in minutes.
                Our AI-powered system will evaluate your application instantly.

                — SmartLend Team
                """, userName);

        String html = String.format("""
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <div style="background:#6d28d9;padding:24px;border-radius:8px 8px 0 0">
                    <h1 style="color:#fff;margin:0">Welcome to SmartLend!</h1>
                  </div>
                  <div style="padding:24px;background:#f9fafb;border:1px solid #e5e7eb;border-top:none">
                    <p style="font-size:16px">Hi <strong>%s</strong>,</p>
                    <p>Your account has been created successfully. You're all set to get started.</p>
                    <ul style="padding-left:20px;color:#374151">
                      <li>Apply for a loan in under 2 minutes</li>
                      <li>AI-powered instant credit scoring</li>
                      <li>Track your EMI schedule in real time</li>
                    </ul>
                    <a href="https://smartlend-ebon.vercel.app/dashboard"
                       style="display:inline-block;background:#6d28d9;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;font-weight:bold;margin-top:16px">
                      Go to Dashboard
                    </a>
                    <p style="margin-top:24px;font-size:14px;color:#6b7280">— SmartLend Team</p>
                  </div>
                </div>
                """, userName);

        dispatcher.dispatch(new NotificationPayload(
                "USER_REGISTERED", userEmail, userName, userPhone, subject, plain, html,
                Map.of()
        ));
    }

    public void handleLoanApplied(Map<String, Object> event) {
        String loanId      = str(event, "loanId");
        String userEmail   = str(event, "userEmail");
        String userPhone   = str(event, "userPhone");
        String userName    = str(event, "userName", "Valued Customer");
        String amount      = str(event, "amount");
        String tenure      = str(event, "tenureMonths");
        String creditScore = str(event, "creditScore");
        String riskLabel   = str(event, "riskLabel");

        String subject = "Loan Application Received — SmartLend";

        String plain = String.format("""
                Dear %s,

                We have received your loan application.

                  Loan ID      : %s
                  Amount       : ₹%s
                  Tenure       : %s months
                  Credit Score : %s (%s)

                Your application is now under review. Our team will notify you once a decision is made.

                — SmartLend Team
                """, userName, loanId, amount, tenure, creditScore, riskLabel);

        String html = String.format("""
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <div style="background:#2563eb;padding:24px;border-radius:8px 8px 0 0">
                    <h1 style="color:#fff;margin:0">Application Received</h1>
                  </div>
                  <div style="padding:24px;background:#f9fafb;border:1px solid #e5e7eb;border-top:none">
                    <p style="font-size:16px">Dear <strong>%s</strong>,</p>
                    <p>We have received your loan application and it is currently under review.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:16px 0">
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Loan ID</td>
                        <td style="padding:10px;font-weight:bold">%s</td>
                      </tr>
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Amount Requested</td>
                        <td style="padding:10px;font-weight:bold">₹%s</td>
                      </tr>
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Tenure</td>
                        <td style="padding:10px;font-weight:bold">%s months</td>
                      </tr>
                      <tr>
                        <td style="padding:10px;color:#6b7280">Credit Score</td>
                        <td style="padding:10px;font-weight:bold">%s <span style="color:#6b7280;font-weight:normal">(%s)</span></td>
                      </tr>
                    </table>
                    <p style="color:#374151">You will be notified by email once a decision has been made.</p>
                    <a href="https://smartlend-ebon.vercel.app/dashboard"
                       style="display:inline-block;background:#2563eb;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;font-weight:bold;margin-top:8px">
                      Track Application
                    </a>
                    <p style="margin-top:24px;font-size:14px;color:#6b7280">— SmartLend Team</p>
                  </div>
                </div>
                """, userName, loanId, amount, tenure, creditScore, riskLabel);

        dispatcher.dispatch(new NotificationPayload(
                "LOAN_APPLIED", userEmail, userName, userPhone, subject, plain, html,
                Map.of("loanId", loanId, "amount", amount, "creditScore", creditScore)
        ));
    }

    public void handleLoanApproved(Map<String, Object> event) {
        String loanId       = str(event, "loanId");
        String userEmail    = str(event, "userEmail");
        String userPhone    = str(event, "userPhone");
        String userName     = str(event, "userName", "Valued Customer");
        String amount       = str(event, "amount");
        String emiAmount    = str(event, "emiAmount", "N/A");
        String tenure       = str(event, "tenureMonths", "N/A");
        String interestRate = str(event, "interestRate", "N/A");

        String subject = "Your SmartLend Loan is Approved!";

        String plain = String.format("""
                Congratulations, %s!

                Your loan application has been approved.

                  Loan ID       : %s
                  Amount        : ₹%s
                  Tenure        : %s months
                  Interest Rate : %s%%
                  Monthly EMI   : ₹%s

                Log in to your dashboard to view your full EMI schedule.

                — SmartLend Team
                """, userName, loanId, amount, tenure, interestRate, emiAmount);

        String html = String.format("""
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <div style="background:#16a34a;padding:24px;border-radius:8px 8px 0 0">
                    <h1 style="color:#fff;margin:0">Loan Approved!</h1>
                  </div>
                  <div style="padding:24px;background:#f9fafb;border:1px solid #e5e7eb;border-top:none">
                    <p style="font-size:16px">Congratulations, <strong>%s</strong>!</p>
                    <p>Your loan application has been approved. Here are your details:</p>
                    <table style="width:100%%;border-collapse:collapse;margin:16px 0">
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Loan ID</td>
                        <td style="padding:10px;font-weight:bold">%s</td>
                      </tr>
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Amount</td>
                        <td style="padding:10px;font-weight:bold">₹%s</td>
                      </tr>
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Tenure</td>
                        <td style="padding:10px;font-weight:bold">%s months</td>
                      </tr>
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Interest Rate</td>
                        <td style="padding:10px;font-weight:bold">%s%%</td>
                      </tr>
                      <tr>
                        <td style="padding:10px;color:#6b7280">Monthly EMI</td>
                        <td style="padding:10px;font-weight:bold;color:#16a34a">₹%s</td>
                      </tr>
                    </table>
                    <a href="http://localhost:3000/dashboard"
                       style="display:inline-block;background:#16a34a;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;font-weight:bold">
                      View EMI Schedule
                    </a>
                    <p style="margin-top:24px;font-size:14px;color:#6b7280">— SmartLend Team</p>
                  </div>
                </div>
                """, userName, loanId, amount, tenure, interestRate, emiAmount);

        dispatcher.dispatch(new NotificationPayload(
                "LOAN_APPROVED", userEmail, userName, userPhone, subject, plain, html,
                Map.of("loanId", loanId, "amount", amount, "emiAmount", emiAmount, "tenureMonths", tenure)
        ));
    }

    public void handleLoanRejected(Map<String, Object> event) {
        String loanId     = str(event, "loanId");
        String userEmail  = str(event, "userEmail");
        String userPhone  = str(event, "userPhone");
        String userName   = str(event, "userName", "Valued Customer");

        String subject = "Update on Your SmartLend Loan Application";

        String plain = String.format("""
                Dear %s,

                Thank you for choosing SmartLend.

                Unfortunately, your loan application (ID: %s) could not be approved at this time.

                You are welcome to reapply after 90 days. For further assistance,
                please contact our support team.

                — SmartLend Team
                """, userName, loanId);

        String html = String.format("""
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <div style="background:#dc2626;padding:24px;border-radius:8px 8px 0 0">
                    <h1 style="color:#fff;margin:0">Application Update</h1>
                  </div>
                  <div style="padding:24px;background:#f9fafb;border:1px solid #e5e7eb;border-top:none">
                    <p style="font-size:16px">Dear <strong>%s</strong>,</p>
                    <p>Thank you for choosing SmartLend. After careful review, we are unable
                    to approve your loan application (<strong>%s</strong>) at this time.</p>
                    <p>You may reapply after <strong>90 days</strong> or contact our support
                    team for personalized assistance.</p>
                    <p style="margin-top:24px;font-size:14px;color:#6b7280">— SmartLend Team</p>
                  </div>
                </div>
                """, userName, loanId);

        dispatcher.dispatch(new NotificationPayload(
                "LOAN_REJECTED", userEmail, userName, userPhone, subject, plain, html,
                Map.of("loanId", loanId)
        ));
    }

    public void handleEmiDue(Map<String, Object> event) {
        String loanId    = str(event, "loanId");
        String userEmail = str(event, "userEmail");
        String userPhone = str(event, "userPhone");
        String userName  = str(event, "userName", "Valued Customer");
        String amount    = str(event, "amount");
        String dueDate   = str(event, "dueDate", "soon");

        String subject = "SmartLend EMI Due Reminder";

        String plain = String.format("""
                Dear %s,

                This is a reminder that your SmartLend EMI is due.

                  Loan ID  : %s
                  Amount   : ₹%s
                  Due Date : %s

                Please ensure timely payment to avoid late fees.

                — SmartLend Team
                """, userName, loanId, amount, dueDate);

        String html = String.format("""
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <div style="background:#2563eb;padding:24px;border-radius:8px 8px 0 0">
                    <h1 style="color:#fff;margin:0">EMI Due Reminder</h1>
                  </div>
                  <div style="padding:24px;background:#f9fafb;border:1px solid #e5e7eb;border-top:none">
                    <p style="font-size:16px">Dear <strong>%s</strong>,</p>
                    <p>Your next EMI payment is coming up:</p>
                    <table style="width:100%%;border-collapse:collapse;margin:16px 0">
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">Loan ID</td>
                        <td style="padding:10px;font-weight:bold">%s</td>
                      </tr>
                      <tr style="border-bottom:1px solid #e5e7eb">
                        <td style="padding:10px;color:#6b7280">EMI Amount</td>
                        <td style="padding:10px;font-weight:bold;color:#2563eb">₹%s</td>
                      </tr>
                      <tr>
                        <td style="padding:10px;color:#6b7280">Due Date</td>
                        <td style="padding:10px;font-weight:bold">%s</td>
                      </tr>
                    </table>
                    <p style="font-size:14px;color:#6b7280">
                      Please ensure your account has sufficient funds to avoid late fees.
                    </p>
                    <p style="margin-top:24px;font-size:14px;color:#6b7280">— SmartLend Team</p>
                  </div>
                </div>
                """, userName, loanId, amount, dueDate);

        dispatcher.dispatch(new NotificationPayload(
                "EMI_DUE", userEmail, userName, userPhone, subject, plain, html,
                Map.of("loanId", loanId, "amount", amount, "dueDate", dueDate)
        ));
    }

    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private String str(Map<String, Object> map, String key, String fallback) {
        Object val = map.get(key);
        return val != null && !val.toString().isBlank() ? val.toString() : fallback;
    }
}
