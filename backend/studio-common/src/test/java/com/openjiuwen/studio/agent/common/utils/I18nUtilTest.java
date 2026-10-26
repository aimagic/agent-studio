/* Copyright (c) Huawei Technologies Co., Ltd. 2024-2026. All rights reserved. */

package com.openjiuwen.studio.agent.common.utils;

import com.openjiuwen.studio.agent.common.enums.StudioError;
import com.openjiuwen.studio.agent.common.exception.AgentStudioException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class I18nUtilTest {

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private I18nUtil i18nUtil;

    @Test
    void testGetMessage_ByCode() {
        when(messageSource.getMessage(eq("test.code"), any(Object[].class), eq("test.code"), any(Locale.class)))
                .thenReturn("Test Message");
        String result = i18nUtil.getMessage("test.code");
        assertEquals("Test Message", result);
    }

    @Test
    void testGetMessage_ByStudioError() {
        when(messageSource.getMessage(any(String.class), any(Object[].class), any(String.class), any(Locale.class)))
                .thenReturn("Error Message");
        String result = i18nUtil.getMessage(StudioError.UNEXPECTED_ERROR);
        assertNotNull(result);
    }

    @Test
    void testGetSuggestion_ByStudioError() {
        when(messageSource.getMessage(any(String.class), any(Object[].class), any(Locale.class)))
                .thenReturn("Suggestion text");
        String result = i18nUtil.getSuggestion(StudioError.UNEXPECTED_ERROR);
        assertEquals("Suggestion text", result);
    }

    @Test
    void testGetSuggestion_ExceptionReturnsNull() {
        when(messageSource.getMessage(any(String.class), any(Object[].class), any(Locale.class)))
                .thenThrow(new RuntimeException("not found"));
        String result = i18nUtil.getSuggestion(StudioError.UNEXPECTED_ERROR);
        assertNull(result);
    }

    @Test
    void testGetMessage_ByException() {
        AgentStudioException exception = new AgentStudioException(StudioError.UNEXPECTED_ERROR, "detail");
        when(messageSource.getMessage(any(String.class), any(Object[].class), any(String.class), any(Locale.class)))
                .thenReturn("msg");
        ErrorInfo result = i18nUtil.getMessage(exception);
        assertNotNull(result);
    }
}
