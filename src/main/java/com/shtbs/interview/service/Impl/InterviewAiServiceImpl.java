package com.shtbs.interview.service.Impl;

import com.shtbs.interview.ai.AnswerAnalysis;
import com.shtbs.interview.ai.InterviewEvaluationResult;
import com.shtbs.interview.entity.InterviewMessage;
import com.shtbs.interview.enums.InterviewDifficulty;
import com.shtbs.interview.enums.InterviewRole;
import com.shtbs.interview.service.InterviewAiService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InterviewAiServiceImpl implements InterviewAiService {

    private final ChatClient chatClient;

    public InterviewAiServiceImpl(ChatClient.Builder builder){
        this.chatClient=builder.build();
    }

    public String generateFirstQuestion(
            String category,String difficulty
    ){
        return chatClient
                .prompt()
                .system("""
                    你是一名Java后端技术面试官。

                    你正在进行真实的技术模拟面试。

                    要求：
                    1. 一次只问一道题
                    2. 不要告诉候选人答案
                    3. 不要给提示
                    4. 问题应该适合指定难度
                    5. 只输出面试问题
                    """)
                .user("""
                    面试方向：%s
                    面试难度：%s

                    请生成第一道面试题。
                    """.formatted(category, difficulty))
                .call()
                .content();
    }

  /*  public String generateFollowUpQuestion(
            String category,
            InterviewDifficulty difficulty,
            String answer) {

        return chatClient
                .prompt()
                .system("""
                    你是一名Java后端技术面试官。

                    你正在进行真实的技术模拟面试。

                    要求：
                    1. 根据候选人的回答继续追问
                    2. 一次只问一道题
                    3. 不要直接告诉候选人答案
                    4. 不要给出提示
                    5. 问题符合当前面试难度
                    6. 只输出下一道问题
                    """)
                .user("""
                    面试方向：%s
                    面试难度：%s

                    候选人的回答：
                    %s

                    请根据候选人的回答生成下一道追问题。
                    """.formatted(
                        category,
                        difficulty,
                        answer
                ))
                .call()
                .content();
    }*/

    public AnswerAnalysis analyzeAnswer(
            String category,
            InterviewDifficulty difficulty,
            List<InterviewMessage> messages) {

        StringBuilder history = new StringBuilder();

        for (InterviewMessage message : messages) {

            if (message.getRole() == InterviewRole.INTERVIEWER) {
                history.append("面试官：");
            } else {
                history.append("候选人：");
            }

            history.append(message.getContent())
                    .append("\n");
        }

        return chatClient
                .prompt()
                .system("""
                    你是一名专业的Java后端技术面试官。

                    你的任务是分析候选人最后一次回答，
                    并决定下一步面试行为。

                    评分规则：
                    - score：0~100
                    - correct：回答核心内容是否正确
                    - complete：回答是否完整
                    - needFollowUp：是否值得继续追问当前知识点
                    - missingPoints：缺少的重要知识点
                    - nextQuestion：下一道面试题

                    决策规则：

                    如果候选人的回答还有重要知识点没有覆盖，
                    needFollowUp = true，
                    nextQuestion 应继续追问当前知识点。

                    如果当前知识点已经回答得比较完整，
                    needFollowUp = false，
                    nextQuestion 应切换到新的相关知识点。

                    禁止直接告诉候选人答案。
                    一次只能问一道题。
                    """)
                .user("""
                    面试方向：%s

                    面试难度：%s

                    当前完整面试记录：

                    %s

                    请分析候选人最后一次回答，
                    并决定下一步面试问题。
                    """.formatted(
                        category,
                        difficulty,
                        history
                ))
                .call()
                .entity(AnswerAnalysis.class);
    }

    public InterviewEvaluationResult evaluateInterview(
            String category,
            InterviewDifficulty difficulty,
            List<InterviewMessage> messages) {

        StringBuilder history = new StringBuilder();

        for (InterviewMessage message : messages) {

            if (message.getRole() == InterviewRole.INTERVIEWER) {
                history.append("面试官：");
            } else {
                history.append("候选人：");
            }

            history.append(message.getContent())
                    .append("\n");
        }

        return chatClient
                .prompt()
                .system("""
                    你是一名专业的Java后端技术面试官。

                    现在一场技术模拟面试已经结束。

                    请根据整场面试记录，对候选人的表现进行综合评价。

                    要求：

                    1. score 为 0~100 的整数
                    2. strengths 列出候选人表现较好的地方
                    3. weaknesses 列出知识薄弱点
                    4. suggestions 给出具体学习建议
                    5. 评价必须基于面试记录
                    6. 不要评价面试记录中没有涉及的内容
                    """)
                .user("""
                    面试方向：%s

                    面试难度：%s

                    完整面试记录：

                    %s

                    请对本次面试进行最终评价。
                    """.formatted(
                        category,
                        difficulty,
                        history
                ))
                .call()
                .entity(InterviewEvaluationResult.class);
    }
}
