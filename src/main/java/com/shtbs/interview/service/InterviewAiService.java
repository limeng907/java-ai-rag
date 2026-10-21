package com.shtbs.interview.service;

import com.shtbs.interview.ai.AnswerAnalysis;
import com.shtbs.interview.ai.InterviewEvaluationResult;
import com.shtbs.interview.entity.InterviewMessage;
import com.shtbs.interview.enums.InterviewDifficulty;

import java.util.List;

public interface InterviewAiService {
    String generateFirstQuestion(
            String category,
            String difficulty
    );

    /*String generateFollowUpQuestion(
            String category,
            InterviewDifficulty difficulty,
            String answer
    );*/

    AnswerAnalysis analyzeAnswer(
            String category,
            InterviewDifficulty difficulty,
            List<InterviewMessage> messages
    );

    InterviewEvaluationResult evaluateInterview(
            String category,
            InterviewDifficulty difficulty,
            List<InterviewMessage> messages
    );
}
