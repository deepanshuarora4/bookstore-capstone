package com.capstone.bookstore.payment;

import org.springframework.http.HttpStatus;

import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.common.ErrorCode;

/**
 * 402 PAYMENT_DECLINED. A dedicated type so that the payment transaction can commit the FAILED payment record
 * even though this exception is thrown ({@code noRollbackFor}); every other error still rolls back.
 */
public class PaymentDeclinedException extends ApiException {

    public PaymentDeclinedException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, ErrorCode.PAYMENT_DECLINED, message);
    }
}
