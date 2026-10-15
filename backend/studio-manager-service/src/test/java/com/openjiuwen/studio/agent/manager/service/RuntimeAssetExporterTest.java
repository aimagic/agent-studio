/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.studio.agent.manager.service;

import com.openjiuwen.studio.agent.common.bo.AgentMetadata;
import com.openjiuwen.studio.agent.common.constant.Constants;
import com.openjiuwen.studio.agent.common.exception.AgentStudioException;
import com.openjiuwen.studio.agent.common.enums.StudioError;
import com.openjiuwen.studio.agent.common.utils.PublishUtils;
import com.openjiuwen.studio.agent.common.utils.RequestContextUtils;
import com.openjiuwen.studio.agent.manager.entity.Agent;
import com.openjiuwen.studio.agent.manager.entity.MappingEntity;
import com.openjiuwen.studio.agent.manager.entity.ReleaseVersion;
import com.openjiuwen.studio.agent.manager.entity.WorkflowEntity;
import com.openjiuwen.studio.agent.manager.mapper.AgentMapper;
import com.openjiuwen.studio.agent.manager.mapper.MappingMapper;
import com.openjiuwen.studio.agent.manager.mapper.ReleaseVersionMapper;
import com.openjiuwen.studio.agent.manager.mapper.WorkflowMapper;
import com.openjiuwen.studio.agent.manager.obs.MgObsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RuntimeAssetExporter 单元测试（覆盖 SDD 8.3 测试要点）。
 * mock 依赖：MappingMapper / MgObsService / ReleaseVersionMapper / PublishUtils +
 * RequestContextUtils（静态）。验证版本解析、三类型分流、t_mapping 收集、
 * model-auth/KB/skill 收集、缺失终止、元数据重写、并发、上限。
 */
class RuntimeAssetExporterTest {

    private MappingMapper mappingMapper;
    private MgObsService mgObsService;
    private ReleaseVersionMapper releaseVersionMapper;
    private PublishUtils publishUtils;
    private AgentMapper agentMapper;
    private WorkflowMapper workflowMapper;
    private RuntimeAssetExporter exporter;

    private static final String PROJECT_ID = "proj-001";
    private static final String WORKSPACE_ID = "ws-001";
    private static final String AGENT_ID = "agent-uuid-001";
    private static final String VERSION = "1728465600000";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mappingMapper = mock(MappingMapper.class);
        mgObsService = mock(MgObsService.class);
        releaseVersionMapper = mock(ReleaseVersionMapper.class);
        publishUtils = mock(PublishUtils.class);
        agentMapper = mock(AgentMapper.class);
        workflowMapper = mock(WorkflowMapper.class);
        exporter = new RuntimeAssetExporter();
        ReflectionTestUtils.setField(exporter, "mappingMapper", mappingMapper);
        ReflectionTestUtils.setField(exporter, "mgObsService", mgObsService);
        ReflectionTestUtils.setField(exporter, "releaseVersionMapper", releaseVersionMapper);
        ReflectionTestUtils.setField(exporter, "publishUtils", publishUtils);
        ReflectionTestUtils.setField(exporter, "agentMapper", agentMapper);
        ReflectionTestUtils.setField(exporter, "workflowMapper", workflowMapper);
        // @PostConstruct 不会在 new 时触发，手动初始化默认配置 + Semaphore（与 application-manager.yml 默认值一致）
        ReflectionTestUtils.setField(exporter, "maxAssets", 100);
        ReflectionTestUtils.setField(exporter, "maxConcurrent", 5);
        ReflectionTestUtils.invokeMethod(exporter, "initSemaphore");
        // 归属校验默认放行（所有 agent/workflow 用例复用）；越权用例可各自 override 为 null
        when(agentMapper.selectByProjectIdAndWorkspaceId(anyString(), anyString(), anyString()))
            .thenReturn(new Agent());
        when(workflowMapper.selectByWorkflowIds(anyString(), anyString(), anyList()))
            .thenReturn(List.of(new WorkflowEntity()));
    }

    /** 构造一个基础 agent IR JSON（含 modelConfig） */
    private String agentIrJson() {
        Map<String, Object> ir = new HashMap<>();
        ir.put("agentId", AGENT_ID);
        Map<String, Object> configs = new HashMap<>();
        Map<String, Object> modelConfig = new HashMap<>();
        modelConfig.put("modelName", "model-svc-001");
        Map<String, Object> ext = new HashMap<>();
        ext.put("authId", "auth-001");
        modelConfig.put("extension", ext);
        configs.put("modelConfig", modelConfig);
        ir.put("configs", configs);
        return com.alibaba.fastjson2.JSON.toJSONString(ir);
    }

    private String metadataJson(String versionId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("versionId", versionId);
        meta.put("agentId", AGENT_ID);
        return com.alibaba.fastjson2.JSON.toJSONString(meta);
    }

    private MappingEntity mapping(String resType, String resId, String resVer) {
        MappingEntity m = new MappingEntity();
        m.setResourceType(resType);
        m.setResourceId(resId);
        m.setResourceVersion(resVer);
        m.setValid(true);
        return m;
    }

    /** 默认 mock：版本解析（latest）+ 根 IR + metadata 存在 + model-service 存在 */
    private void mockDefaultAgent() {
        AgentMetadata meta = AgentMetadata.builder().versionId(VERSION).agentId(AGENT_ID).build();
        when(publishUtils.getPublish(eq(AGENT_ID), eq(Constants.LATEST_PUBLISH_VERSION), any())).thenReturn(meta);
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any()))
            .thenReturn(new ArrayList<>());
        String rootIrKey = "agent/ir/" + AGENT_ID + "/" + AGENT_ID + "_" + VERSION + ".json";
        when(mgObsService.getObject(rootIrKey)).thenReturn(agentIrJson());
        String metadataKey = "metadata/" + AGENT_ID + "/" + AGENT_ID + "_latest.json";
        when(mgObsService.getObject(metadataKey)).thenReturn(metadataJson(VERSION));
        when(mgObsService.uploadStreamStagingBucket(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn("export/url.zip");
    }

    // ===== SDD 8.3: 版本解析 =====

    @Test
    void testResolveVersion_latest_resolvesViaPublishUtils() {
        mockDefaultAgent();
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            byte[] zipBytes = exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null);
            // 断言 resolvedVersion 传给了 mapper（非 null/latest）
            verify(mappingMapper).selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any());
            assertNotNull(zipBytes);
        }
    }

    @Test
    void testResolveVersion_notPublished_throws() {
        when(publishUtils.getPublish(eq(AGENT_ID), eq(Constants.LATEST_PUBLISH_VERSION), any())).thenReturn(null);
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            assertThrows(Exception.class, () -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null));
        }
    }

    @Test
    void testResolveVersion_concreteVersion_validatesExistence() {
        ReleaseVersion rv = new ReleaseVersion();
        rv.setAppId(AGENT_ID);
        rv.setVersionId(VERSION);
        when(releaseVersionMapper.selectByAppIdAndVersionId(AGENT_ID, VERSION)).thenReturn(rv);
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any()))
            .thenReturn(new ArrayList<>());
        String rootIrKey = "agent/ir/" + AGENT_ID + "/" + AGENT_ID + "_" + VERSION + ".json";
        when(mgObsService.getObject(rootIrKey)).thenReturn(agentIrJson());
        when(mgObsService.getObject("metadata/" + AGENT_ID + "/" + AGENT_ID + "_latest.json"))
            .thenReturn(metadataJson(VERSION));
        when(mgObsService.uploadStreamStagingBucket(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn("export/url.zip");
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            assertDoesNotThrow(() -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, VERSION));
        }
    }

    @Test
    void testResolveVersion_concreteVersion_notExist_throws() {
        when(releaseVersionMapper.selectByAppIdAndVersionId(AGENT_ID, VERSION)).thenReturn(null);
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            assertThrows(Exception.class, () -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, VERSION));
        }
    }

    // ===== SDD 8.3: 三类型分流 =====

    @Test
    void testRootIr_workflowType_usesWorkflowIrPath() {
        String wfId = "wf-001";
        AgentMetadata meta = AgentMetadata.builder().versionId(VERSION).agentId(wfId).build();
        when(publishUtils.getPublish(eq(wfId), eq(Constants.LATEST_PUBLISH_VERSION), any())).thenReturn(meta);
        when(mappingMapper.selectByAppIdAndAppVersion(eq(wfId), eq(VERSION), any(), any())).thenReturn(new ArrayList<>());
        String wfIrKey = "workflow/ir/" + wfId + "/" + wfId + "_" + VERSION + ".json";
        when(mgObsService.getObject(wfIrKey)).thenReturn("{\"configs\":{}}");
        when(mgObsService.getObject("metadata/" + wfId + "/" + wfId + "_latest.json")).thenReturn(metadataJson(VERSION));
        when(mgObsService.uploadStreamStagingBucket(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn("export/url.zip");
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "workflow", wfId, null);
            // 断言读的是 workflow/ir/ 路径（收集阶段 + 打包阶段各读一次，atLeast）
            verify(mgObsService, atLeast(1)).getObject(wfIrKey);
        }
    }

    // ===== SDD 8.3: t_mapping 收集 + 子 IR =====

    @Test
    void testCollectAssetKeys_workflowMapping_collectsWorkflowIr() {
        mockDefaultAgent();
        List<MappingEntity> mappings = new ArrayList<>();
        mappings.add(mapping("workflow", "sub-wf-001", VERSION));
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any())).thenReturn(mappings);
        String subWfIrKey = "workflow/ir/sub-wf-001/sub-wf-001_" + VERSION + ".json";
        when(mgObsService.getObject(subWfIrKey)).thenReturn("{\"configs\":{}}");
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null);
            verify(mgObsService, atLeast(1)).getObject(subWfIrKey);
        }
    }

    @Test
    void testCollectAssetKeys_modelMapping_collectsModelService() {
        mockDefaultAgent();
        List<MappingEntity> mappings = new ArrayList<>();
        mappings.add(mapping("model", "model-svc-001", null));
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any())).thenReturn(mappings);
        // model-service 文件
        Map<String, Object> ms = new HashMap<>();
        ms.put("provider_id", "prov-001");
        ms.put("project_id", "proj-001");
        ms.put("auth_id", "auth-001");
        when(mgObsService.getObject("model-service/ir/model-svc-001.json"))
            .thenReturn(com.alibaba.fastjson2.JSON.toJSONString(ms));
        // model-auth 文件
        when(mgObsService.getObject("model-auth/auth/proj-001/prov-001/auth-001.json")).thenReturn("{}");
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null);
            verify(mgObsService, atLeast(1)).getObject("model-service/ir/model-svc-001.json");
        }
    }

    // ===== SDD 8.3: 平台模型特判 =====

    @Test
    void testPlatformModel_systemProjectId_usesRootProjectId() {
        mockDefaultAgent();
        List<MappingEntity> mappings = new ArrayList<>();
        mappings.add(mapping("model", "platform-model-001", null));
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any())).thenReturn(mappings);
        Map<String, Object> ms = new HashMap<>();
        ms.put("provider_id", "prov-001");
        ms.put("project_id", "SYSTEM");  // 平台模型
        ms.put("auth_id", "auth-001");
        when(mgObsService.getObject("model-service/ir/platform-model-001.json"))
            .thenReturn(com.alibaba.fastjson2.JSON.toJSONString(ms));
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null);
            // 平台模型 → authProjectId 用根应用 PROJECT_ID 而非 SYSTEM
            verify(mgObsService).getObject("model-auth/auth/" + PROJECT_ID + "/prov-001/auth-001.json");
        }
    }

    // ===== SDD 8.3: 缺失终止 =====

    @Test
    void testRootIrMissing_throws() {
        AgentMetadata meta = AgentMetadata.builder().versionId(VERSION).agentId(AGENT_ID).build();
        when(publishUtils.getPublish(eq(AGENT_ID), eq(Constants.LATEST_PUBLISH_VERSION), any())).thenReturn(meta);
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any())).thenReturn(new ArrayList<>());
        String rootIrKey = "agent/ir/" + AGENT_ID + "/" + AGENT_ID + "_" + VERSION + ".json";
        when(mgObsService.getObject(rootIrKey)).thenThrow(new RuntimeException("OBS 404"));
        when(mgObsService.getObject("metadata/" + AGENT_ID + "/" + AGENT_ID + "_latest.json")).thenReturn(metadataJson(VERSION));
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            assertThrows(Exception.class, () -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null));
        }
    }

    // ===== SDD 8.3: 元数据历史版本重写 =====

    @Test
    void testHistoryExport_rewritesMetadataVersionId() {
        ReleaseVersion rv = new ReleaseVersion();
        rv.setAppId(AGENT_ID);
        rv.setVersionId("old-version-111");
        when(releaseVersionMapper.selectByAppIdAndVersionId(AGENT_ID, "old-version-111")).thenReturn(rv);
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq("old-version-111"), any(), any()))
            .thenReturn(new ArrayList<>());
        String rootIrKey = "agent/ir/" + AGENT_ID + "/" + AGENT_ID + "_old-version-111.json";
        when(mgObsService.getObject(rootIrKey)).thenReturn(agentIrJson());
        // metadata 内容指向当前最新版本（非 old-version-111）
        when(mgObsService.getObject("metadata/" + AGENT_ID + "/" + AGENT_ID + "_latest.json"))
            .thenReturn(metadataJson("new-version-222"));
        when(mgObsService.uploadStreamStagingBucket(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn("export/url.zip");
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            assertDoesNotThrow(() -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, "old-version-111"));
        }
    }

    // ===== SDD 8.3: 并发控制 =====

    @Test
    void testConcurrency_semaphoreLimits() throws Exception {
        mockDefaultAgent();
        // 模拟信号量耗尽：占满 5 个许可
        java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(5);
        sem.acquire(5);
        ReflectionTestUtils.setField(exporter, "exportSemaphore", sem);
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            assertThrows(Exception.class, () -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null));
        } finally {
            sem.release(5);
        }
    }

    // ===== 越权校验（2026-10-14 巡检新增）=====

    @Test
    void testAuthCheck_projectIdMismatch_throws() {
        mockDefaultAgent();
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            // 请求传篡改的 projectId，与 RequestContext 不一致 → 抛越权异常
            AgentStudioException ex = assertThrows(AgentStudioException.class,
                () -> exporter.exportRuntimeAssetZip("evil-project", WORKSPACE_ID, "agent", AGENT_ID, null));
            // 验证抛的是越权错误码（而非其他 AgentStudioException，防误判）
            assertEquals(StudioError.USER_WORKSPACE_PERMISSION_INVALID, ex.getErrorCode());
        }
    }

    @Test
    void testAuthCheck_workspaceIdMismatch_throws() {
        mockDefaultAgent();
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            // 请求传篡改的 workspaceId，与 RequestContext 不一致 → 抛越权异常
            AgentStudioException ex = assertThrows(AgentStudioException.class,
                () -> exporter.exportRuntimeAssetZip(PROJECT_ID, "evil-workspace", "agent", AGENT_ID, null));
            assertEquals(StudioError.USER_WORKSPACE_PERMISSION_INVALID, ex.getErrorCode());
        }
    }

    @Test
    void testAuthCheck_contextProjectIdBlank_skipsCheck() {
        // RequestContext projectId 为空（未鉴权场景）时，不校验、放行（避免误拦）
        mockDefaultAgent();
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn("");
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn("");
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            assertDoesNotThrow(() -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null));
        }
    }

    @Test
    void testMaxConcurrent_zeroConfigured_doesNotDeadlock() {
        // maxConcurrent 配 0 时，@PostConstruct 兜底为 1，导出不永久 busy
        ReflectionTestUtils.setField(exporter, "maxConcurrent", 0);
        ReflectionTestUtils.invokeMethod(exporter, "initSemaphore");
        // maxAssets 保持 setUp 的 100（不被 maxConcurrent 影响）
        mockDefaultAgent();
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            // 配 0 兜底为 1 → 能拿到许可 → 不抛 busy 异常
            assertDoesNotThrow(() -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null));
        }
    }

    // ===== 巡检 #2：resourceId 归属校验（IDOR）=====

    @Test
    void testOwnership_resourceIdNotInWorkspace_throws() {
        mockDefaultAgent();
        // override：resourceId 不归属当前 (projectId, workspaceId) → 越权拒绝
        when(agentMapper.selectByProjectIdAndWorkspaceId(eq(PROJECT_ID), eq(WORKSPACE_ID), eq(AGENT_ID)))
            .thenReturn(null);
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            AgentStudioException ex = assertThrows(AgentStudioException.class,
                () -> exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null));
            assertEquals(StudioError.USER_WORKSPACE_PERMISSION_INVALID, ex.getErrorCode());
        }
    }

    // ===== 巡检 #1：技能 ZIP 二进制完整性（走 readObsFileStream，不走文本通道）=====

    @Test
    void testSkillBinary_readStreamNotTextChannel() throws Exception {
        mockDefaultAgent();
        String skillPath = "user-1/skills/sk-1/v1/skill.zip";
        String rootIrKey = "agent/ir/" + AGENT_ID + "/" + AGENT_ID + "_" + VERSION + ".json";
        // 根 IR 内嵌 skill_info.skill_path
        when(mgObsService.getObject(rootIrKey)).thenReturn(agentIrWithSkillJson(skillPath));
        // 技能 ZIP 二进制：含非法 UTF-8 字节（经文本通道会损坏/抛异常）
        byte[] skillBytes = new byte[] {(byte) 0x80, (byte) 0xFF, 0x01, 0x02, (byte) 0xC0, (byte) 0xC1, 0x7F, 0x00};
        when(mgObsService.readObsFileStream(skillPath)).thenReturn(new ByteArrayInputStream(skillBytes));
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            byte[] zip = exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null);
            assertNotNull(zip);
            // 关键断言：技能不走文本通道（getObject/UTF-8 解码会损坏二进制）
            verify(mgObsService, never()).getObject(skillPath);
            // 解压校验：技能 entry 字节与原字节完全一致（未被 UTF-8 解码破坏）
            byte[] extracted = extractZipEntry(zip, skillPath);
            assertNotNull(extracted, "skill entry not found in zip");
            assertArrayEquals(skillBytes, extracted);
        }
    }

    // ===== 巡检 #3/#4：子 IR 版本以父 IR 内嵌 ir_path 为准 + note =====

    @Test
    void testSubIrVersionConflict_irPathWins() throws Exception {
        mockDefaultAgent();
        String rootIrKey = "agent/ir/" + AGENT_ID + "/" + AGENT_ID + "_" + VERSION + ".json";
        String oldVerKey = "workflow/ir/sub-wf-001/sub-wf-001_old-ver-000.json";
        String newVerKey = "workflow/ir/sub-wf-001/sub-wf-001_new-ver-999.json";
        // t_mapping 给子工作流 old-ver；根 IR 内嵌 ir_path 指向 new-ver
        List<MappingEntity> mappings = new ArrayList<>();
        mappings.add(mapping("workflow", "sub-wf-001", "old-ver-000"));
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any())).thenReturn(mappings);
        when(mgObsService.getObject(oldVerKey)).thenReturn("{\"configs\":{}}");
        when(mgObsService.getObject(newVerKey)).thenReturn("{\"configs\":{}}");
        when(mgObsService.getObject(rootIrKey)).thenReturn(agentIrWithWorkflowsJson(newVerKey));
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            byte[] zip = exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null);
            Set<String> names = zipEntryNames(zip);
            // ir_path 胜出：new-ver 入包，old-ver 不入包
            assertTrue(names.contains(newVerKey), "ir_path version should be in zip");
            assertFalse(names.contains(oldVerKey), "t_mapping version should NOT be in zip");
            // manifest 中该 asset note 非空（记录版本对齐差异）
            assertTrue(manifestAssetNoteContains(zip, newVerKey, "ir_path"),
                "manifest note should record version alignment");
        }
    }

    // ===== 巡检 #7：STRATEGY auth_id_list 逐子模型鉴权收集 =====

    @Test
    void testStrategyAuthIdList_collected() {
        mockDefaultAgent();
        List<MappingEntity> mappings = new ArrayList<>();
        mappings.add(mapping("strategy", "strategy-001", null));
        when(mappingMapper.selectByAppIdAndAppVersion(eq(AGENT_ID), eq(VERSION), any(), any())).thenReturn(mappings);
        // 路由策略文件：service_id_list 两个子模型，auth_id_list 一一对应
        when(mgObsService.getObject("model-service/ir/strategy-001.json"))
            .thenReturn(routerStrategyJson("sub-ms-1,sub-ms-2", "auth-a,auth-b", "prov-1", PROJECT_ID));
        // 子模型 model-service 文件（不含 auth_metadata_id，强制走 explicitAuthId）
        when(mgObsService.getObject("model-service/ir/sub-ms-1.json"))
            .thenReturn(modelServiceJson("prov-1", PROJECT_ID, null));
        when(mgObsService.getObject("model-service/ir/sub-ms-2.json"))
            .thenReturn(modelServiceJson("prov-1", PROJECT_ID, null));
        // 鉴权文件
        when(mgObsService.getObject("model-auth/auth/" + PROJECT_ID + "/prov-1/auth-a.json")).thenReturn("{}");
        when(mgObsService.getObject("model-auth/auth/" + PROJECT_ID + "/prov-1/auth-b.json")).thenReturn("{}");
        try (MockedStatic<RequestContextUtils> mocked = Mockito.mockStatic(RequestContextUtils.class)) {
            mocked.when(RequestContextUtils::getRequestProjectId).thenReturn(PROJECT_ID);
            mocked.when(RequestContextUtils::getRequestWorkspaceId).thenReturn(WORKSPACE_ID);
            mocked.when(RequestContextUtils::getRequestUserId).thenReturn("testUser");
            exporter.exportRuntimeAssetZip(PROJECT_ID, WORKSPACE_ID, "agent", AGENT_ID, null);
            // auth_id_list 中的 authId 对应的 model-auth 被收集
            verify(mgObsService, atLeast(1)).getObject("model-auth/auth/" + PROJECT_ID + "/prov-1/auth-a.json");
            verify(mgObsService, atLeast(1)).getObject("model-auth/auth/" + PROJECT_ID + "/prov-1/auth-b.json");
        }
    }

    // ===== 辅助：IR 构建 =====

    private String agentIrWithWorkflowsJson(String childIrPath) {
        Map<String, Object> ir = new HashMap<>();
        ir.put("agentId", AGENT_ID);
        Map<String, Object> wf = new HashMap<>();
        wf.put("ir_path", childIrPath);
        Map<String, Object> configs = new HashMap<>();
        configs.put("workflows", List.of(wf));
        ir.put("configs", configs);
        return com.alibaba.fastjson2.JSON.toJSONString(ir);
    }

    private String agentIrWithSkillJson(String skillPath) {
        Map<String, Object> ir = new HashMap<>();
        ir.put("agentId", AGENT_ID);
        Map<String, Object> skill = new HashMap<>();
        skill.put("skill_path", skillPath);
        Map<String, Object> skills = new HashMap<>();
        skills.put("skill_info", List.of(skill));
        Map<String, Object> configs = new HashMap<>();
        configs.put("skills", skills);
        ir.put("configs", configs);
        return com.alibaba.fastjson2.JSON.toJSONString(ir);
    }

    private String modelServiceJson(String providerId, String projectId, String authMetadataId) {
        Map<String, Object> data = new HashMap<>();
        data.put("provider_id", providerId);
        data.put("project_id", projectId);
        if (authMetadataId != null) {
            data.put("auth_metadata_id", authMetadataId);
        }
        Map<String, Object> ms = new HashMap<>();
        ms.put("type", "model");
        ms.put("data", data);
        return com.alibaba.fastjson2.JSON.toJSONString(ms);
    }

    private String routerStrategyJson(String serviceIdList, String authIdList, String providerId, String projectId) {
        Map<String, Object> data = new HashMap<>();
        data.put("service_id_list", serviceIdList);
        data.put("auth_id_list", authIdList);
        data.put("provider_id", providerId);
        data.put("project_id", projectId);
        Map<String, Object> ms = new HashMap<>();
        ms.put("type", "router");
        ms.put("data", data);
        return com.alibaba.fastjson2.JSON.toJSONString(ms);
    }

    // ===== 辅助：ZIP 解析 =====

    private Set<String> zipEntryNames(byte[] zip) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private byte[] extractZipEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return zis.readAllBytes();
                }
            }
        }
        return null;
    }

    private boolean manifestAssetNoteContains(byte[] zip, String assetKey, String fragment) throws Exception {
        byte[] manifestBytes = extractZipEntry(zip, "manifest.json");
        if (manifestBytes == null) {
            return false;
        }
        com.alibaba.fastjson2.JSONObject manifest = com.alibaba.fastjson2.JSON
            .parseObject(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8));
        com.alibaba.fastjson2.JSONArray assets = manifest.getJSONArray("assets");
        if (assets == null) {
            return false;
        }
        for (int i = 0; i < assets.size(); i++) {
            com.alibaba.fastjson2.JSONObject a = assets.getJSONObject(i);
            if (assetKey.equals(a.getString("key"))) {
                String note = a.getString("note");
                return note != null && note.contains(fragment);
            }
        }
        return false;
    }
}
