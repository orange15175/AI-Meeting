package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 转换器：题号必须由数组顺序生成，题干/分类/难度/要点/依据全部用同一个题号绑定。
 */
class InterviewQuestionBatchConverterTest {

    private final InterviewQuestionBatchConverter converter = new InterviewQuestionBatchConverter();

    @Test
    @DisplayName("题号从 1 连续生成，题干、分类、难度、要点、依据按同一题号对齐")
    void bindsByArrayOrder() {
        InterviewQuestionBatch batch = new InterviewQuestionBatch(
                List.of(
                        new InterviewQuestionDraft("第一题题干", "PROJECT", "MEDIUM", "库存",
                                "RESUME", "负责订单与库存模块", "熟悉高并发", List.of("要点A", "要点B")),
                        new InterviewQuestionDraft("第二题题干", "FUNDAMENTAL", "EASY", "索引",
                                "NONE", "", "", List.of("要点C", "要点D"))),
                Map.of(),
                List.of("建议一", "建议二", "建议三"),
                "Java 后端",
                78,
                "三年 Java 后端经验");

        InterviewQuestionBatchConverter.Conversion conversion = converter.convert(batch, false);

        // 题号/题干/分类/难度同源：这一组断言拦住"分类难度按另一套下标错位"的回归。
        assertThat(conversion.questions())
                .extracting(InterviewQuestionStore.MainQuestion::questionNo,
                        InterviewQuestionStore.MainQuestion::content,
                        InterviewQuestionStore.MainQuestion::category,
                        InterviewQuestionStore.MainQuestion::difficulty)
                .containsExactly(
                        tuple("1", "第一题题干", "PROJECT", "MEDIUM"),
                        tuple("2", "第二题题干", "FUNDAMENTAL", "EASY"));
        assertThat(conversion.questionPoints()).containsOnlyKeys("1", "2");
        assertThat(conversion.questionPoints().get("1")).containsExactly("要点A", "要点B");
        assertThat(conversion.questionPoints().get("2")).containsExactly("要点C", "要点D");
        assertThat(conversion.questionTargets()).containsOnlyKeys("1", "2");
        assertThat(targetOf(conversion, "1"))
                .containsEntry("source", "RESUME")
                .containsEntry("evidence", "负责订单与库存模块")
                .containsEntry("requirement", "熟悉高并发");
        // 无 JD 时不写 requirement，也不伪造证据
        assertThat(targetOf(conversion, "2")).containsEntry("source", "NONE").doesNotContainKey("evidence");
        assertThat(conversion.jdContext()).isEmpty();
        assertThat(conversion.resumeContext())
                .containsEntry("type", "Java 后端")
                .containsEntry("resumeScore", 78)
                .containsEntry("resumeSummary", "三年 Java 后端经验");
    }

    @Test
    @DisplayName("有 JD 时保留岗位上下文")
    void keepsJdContextWhenPresent() {
        InterviewQuestionBatch batch = new InterviewQuestionBatch(
                List.of(new InterviewQuestionDraft("题干", "JOB", "MEDIUM", "缓存",
                        "JD", "熟悉 Redis", "熟悉 Redis", List.of("要点A", "要点B"))),
                Map.of("jobTitle", "Java 后端工程师"),
                List.of("建议一", "建议二", "建议三"),
                "Java 后端",
                80,
                "画像");

        InterviewQuestionBatchConverter.Conversion conversion = converter.convert(batch, true);

        assertThat(conversion.jdContext()).containsEntry("jobTitle", "Java 后端工程师");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> targetOf(InterviewQuestionBatchConverter.Conversion conversion, String questionNo) {
        return (Map<String, Object>) conversion.questionTargets().get(questionNo);
    }
}
