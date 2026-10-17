package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * An answer slice travels as an SSE {@code data} field, and that field cannot carry a raw newline:
 * the emitter writes a String verbatim, so an embedded newline ends the field and dumps the rest of
 * the slice onto a line with no {@code data:} prefix — which a spec-following client discards. That
 * is how an answer with headings, bullets and citation marks reached the embedded widget as a single
 * run-together line. These tests pin the framing that prevents it.
 */
class ExternalStreamServiceTest {

    /** The chunks concatenated in order are exactly the bytes a client receives. */
    private static String wire(SseEmitter.SseEventBuilder builder) {
        StringBuilder wire = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType chunk : builder.build()) {
            wire.append(chunk.getData());
        }
        return wire.toString();
    }

    /** What a spec-following client does: take each {@code data:} value, join them with \n. */
    private static String joinDataFields(String wire) {
        List<String> fields = new ArrayList<String>();
        for (String line : wire.split("\n", -1)) {
            if (line.startsWith("data:")) {
                fields.add(line.substring(5));
            }
        }
        return String.join("\n", fields);
    }

    @Test
    void everyLineOfASliceGetsItsOwnDataField() {
        assertEquals("event:delta\ndata:第一行\ndata:第二行\n\n",
            wire(ExternalStreamService.deltaEvent("第一行\n第二行")));
    }

    @Test
    void joiningTheDataFieldsReproducesTheTextExactly() {
        String text = "##年假天数\n- 09-01之前：[证据1]\n- 09-01起：[证据2]";
        assertEquals(text, joinDataFields(wire(ExternalStreamService.deltaEvent(text))));
    }

    @Test
    void aSliceWithoutNewlinesKeepsItsOldShape() {
        // 没有换行的切片仍然只有一条 data: 字段：老客户端与老日志的样子不变
        assertEquals("event:delta\ndata:年假\n\n", wire(ExternalStreamService.deltaEvent("年假")));
    }

    @Test
    void aTrailingNewlineSurvives() {
        // 结尾换行会多出一个空 data: 字段，拼回来正好还原结尾换行
        assertEquals("行\n", joinDataFields(wire(ExternalStreamService.deltaEvent("行\n"))));
    }
}
