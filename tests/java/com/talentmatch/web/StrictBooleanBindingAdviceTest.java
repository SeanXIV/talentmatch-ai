package com.talentmatch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Standalone MockMvc slice: a probe controller with the same parameter shapes as MatchController
 * ({@code @RequestParam(defaultValue = "false") boolean}) plus a nullable {@code Boolean},
 * with {@link StrictBooleanBindingAdvice} registered as controller advice.
 */
class StrictBooleanBindingAdviceTest {

    @RestController
    static class ProbeController {
        @GetMapping("/primitive")
        String primitive(@RequestParam(name = "flag", defaultValue = "false") boolean flag) {
            return String.valueOf(flag);
        }

        @GetMapping("/boxed")
        String boxed(@RequestParam(name = "flag", required = false) Boolean flag) {
            return String.valueOf(flag);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new StrictBooleanBindingAdvice())
            .build();

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", " true "})
    void acceptsTrueCaseInsensitive(String value) throws Exception {
        mvc.perform(get("/primitive").param("flag", value))
                .andExpect(status().isOk()).andExpect(content().string("true"));
        mvc.perform(get("/boxed").param("flag", value))
                .andExpect(status().isOk()).andExpect(content().string("true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False"})
    void acceptsFalseCaseInsensitive(String value) throws Exception {
        mvc.perform(get("/primitive").param("flag", value))
                .andExpect(status().isOk()).andExpect(content().string("false"));
        mvc.perform(get("/boxed").param("flag", value))
                .andExpect(status().isOk()).andExpect(content().string("false"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "no", "on", "off", "1", "0", "y", "n", "truee", "t", "f"})
    void rejectsLenientSpellings(String value) throws Exception {
        for (String path : new String[] {"/primitive", "/boxed"}) {
            MvcResult result = mvc.perform(get(path).param("flag", value))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(result.getResolvedException())
                    .as("%s?flag=%s", path, value)
                    .isInstanceOf(MethodArgumentTypeMismatchException.class);
        }
    }

    @Test
    void missingPrimitiveParamUsesDefaultFalse() throws Exception {
        mvc.perform(get("/primitive")).andExpect(status().isOk()).andExpect(content().string("false"));
    }

    @Test
    void emptyPrimitiveParamUsesDefaultFalse() throws Exception {
        mvc.perform(get("/primitive").param("flag", ""))
                .andExpect(status().isOk()).andExpect(content().string("false"));
    }

    @Test
    void missingOrEmptyBoxedParamIsNull() throws Exception {
        mvc.perform(get("/boxed")).andExpect(status().isOk()).andExpect(content().string("null"));
        mvc.perform(get("/boxed").param("flag", ""))
                .andExpect(status().isOk()).andExpect(content().string("null"));
    }
}
