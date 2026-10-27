/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.studio.agent.common.storage;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.ObsObject;
import com.openjiuwen.studio.agent.common.enums.StudioError;
import com.openjiuwen.studio.agent.common.exception.AgentStudioException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ObsFileStoreImpl.read 测试：404 返回 null、5xx 抛 OBS_FAILED、正常读取返回内容。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ObsFileStoreImplTest {

    @Mock
    private ObsClient obsClient;

    private ObsFileStoreImpl fileStore;

    @BeforeEach
    void setUp() {
        // path 含 "/" 时 splitPath 用前缀作 bucket，不依赖 obsConfig.getBucket()。
        fileStore = new ObsFileStoreImpl(null);
        ReflectionTestUtils.setField(fileStore, "obsClient", obsClient);
    }

    /** OBS 404 → 返回 null，不抛异常。 */
    @Test
    void read_obsNotFound404_returnsNull() {
        when(obsClient.getObject(eq("bucket"), eq("missing-key")))
            .thenThrow(obsException(404));
        assertNull(fileStore.read("bucket/missing-key"),
            "OBS 404 应返回 null（对象不存在），不抛 500");
    }

    /** 真实 OBS 故障（非 404，如 500）→ 抛 OBS_FAILED。 */
    @Test
    void read_obsFailure500_throwsObsFailed() {
        when(obsClient.getObject(eq("bucket"), eq("key")))
            .thenThrow(obsException(500));
        AgentStudioException thrown = assertThrows(AgentStudioException.class,
            () -> fileStore.read("bucket/key"));
        assertEquals(StudioError.OBS_FAILED, thrown.getErrorCode(),
            "真实 OBS 故障应抛 OBS_FAILED(500)，不降级为 null");
    }

    /** 正常读取 → 返回内容。 */
    @Test
    void read_success_returnsContent() {
        ObsObject obj = new ObsObject();
        obj.setObjectContent(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(obsClient.getObject(eq("bucket"), eq("key"))).thenReturn(obj);
        assertEquals("hello", fileStore.read("bucket/key"));
    }

    /** 构造指定 responseCode 的 ObsException（SDK 构造器 + 反射设 responseCode）。 */
    private static ObsException obsException(int responseCode) {
        ObsException e = new ObsException("test-" + responseCode);
        ReflectionTestUtils.setField(e, "responseCode", responseCode);
        return e;
    }
}
