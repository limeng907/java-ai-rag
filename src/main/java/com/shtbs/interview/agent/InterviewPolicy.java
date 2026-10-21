package com.shtbs.interview.agent;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.enums.InterviewStatus;
import com.shtbs.interview.exception.BusinessException;

public final class InterviewPolicy {
    public static final int MAX_ROUNDS=7;
    public record Next(int rounds,int skipStreak,InterviewStatus status) {}
    private InterviewPolicy() {}

    public static Next afterAnswer(int completed,int skips,boolean skipped) {
        if (completed<0 || completed>=MAX_ROUNDS || skips<0 || skips>=2) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_IN_PROGRESS);
        }
        int rounds=completed+1;
        int streak=skipped?skips+1:0;
        return new Next(rounds,streak,rounds>=MAX_ROUNDS || streak>=2?InterviewStatus.REVIEW:InterviewStatus.IN_PROGRESS);
    }
}
