/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.agent.manager.exception.contract;

import com.openjiuwen.studio.agent.common.dto.ErrorRsp;
import com.openjiuwen.studio.agent.common.dto.ErrorDetail;
import com.openjiuwen.studio.agent.common.error.ErrorDescriptor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * COM-03 §5: Manager 普通 HTTP 错误响应构建器。
 * <p>
 * 只负责序列化：接受 ErrorDescriptor + Locale，一次产生 ResponseEntity。
 * 不重新读取入站 Header，不调用 UUID，不从 exception message 推断状态/文案，
 * 不序列化 cause/downstream_*，不自行记录第二条错误日志。
 */
public class ManagerHttpErrorResponseBuilder {

    @FunctionalInterface
    public interface I18nResolver {
        String resolve(String key, Object[] args, Locale locale);
    }

    private final I18nResolver resolver;

    public ManagerHttpErrorResponseBuilder(I18nResolver resolver) {
        this.resolver = resolver;
    }

    public ResponseEntity<ErrorRsp> build(ErrorDescriptor descriptor, Locale locale) {
        // 从异常 cause 提取 i18n 占位符 args（资源未发布时为资源类型等业务参数）。
        // fromAgentStudioException 把 AgentStudioException 传给 descriptor.getCause()，
        // 其 getArgs() 携带占位符填充值。无 cause 或非 AgentStudioException 时 args=null。
        // message/reason/suggestion 三段共用同一份 args：主要是 message 段模板有 {0} 占位符
        // （如"{0}资源不存在"）；当前 reason/suggestion 模板无占位符，多传的 args 被
        // MessageFormat 忽略，无副作用——三段同传与 I18nUtil.getMessage(AgentStudioException)
        // 约定对齐，为未来 reason/suggestion 增加占位符预留。args 不足时 I18nUtil catch 返回 code 兜底，不抛。
        Object[] args = extractArgs(descriptor);
        String errorMsg = safeResolve(descriptor.getMessageKey(), args, locale);
        String errorReason = safeResolve(descriptor.getReasonKey(), args, locale);
        String errorSuggestion = safeResolve(descriptor.getSuggestionKey(), args, locale);

        ErrorRsp rsp = new ErrorRsp()
            .setErrorCode(descriptor.getErrorCode())
            .setErrorMsg(errorMsg)
            .setErrorReason(errorReason)
            .setErrorSuggestion(errorSuggestion)
            .setRequestId(descriptor.getRequestId());

        if (descriptor.getSafeDetails() != null) {
            List<ErrorDetail> copies = new ArrayList<>(descriptor.getSafeDetails());
            rsp.setDetails(copies);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", descriptor.getRequestId());

        return new ResponseEntity<>(rsp, headers, HttpStatus.valueOf(descriptor.getHttpStatus()));
    }

    /**
     * COM-03 §4: fail-closed——resolver 异常时抛出,不返回空串。
     * 调用方 (build) 的调用者 (handler/listener) 应捕获并提供硬编码安全响应。
     */
    private String safeResolve(String key, Object[] args, Locale locale) {
        String result = resolver.resolve(key, args, locale);
        if (result == null || result.isBlank()) {
            throw new IllegalStateException(
                "i18n resolution returned blank for key=" + key + " locale=" + locale);
        }
        return result;
    }

    /**
     * 从 descriptor.cause 提取 i18n 占位符 args。
     * message/reason/suggestion 三段共用同一份 args 填占位符（与
     * I18nUtil.getMessage(AgentStudioException) 约定对齐）。cause 非 AgentStudioException
     * 时返回 null（无占位符文案不受影响）。
     */
    private Object[] extractArgs(ErrorDescriptor descriptor) {
        Throwable cause = descriptor.getCause();
        if (cause instanceof com.openjiuwen.studio.agent.common.exception.AgentStudioException ase) {
            return ase.getArgs();
        }
        return null;
    }
}
