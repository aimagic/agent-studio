/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.studio.agent.manager.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.openjiuwen.studio.agent.common.constant.Constants;
import com.openjiuwen.studio.agent.common.enums.StudioError;
import com.openjiuwen.studio.agent.common.exception.AgentStudioException;
import com.openjiuwen.studio.agent.common.utils.PublishUtils;
import com.openjiuwen.studio.agent.common.utils.RequestContextUtils;
import com.openjiuwen.studio.agent.manager.constant.CommonConstant;
import com.openjiuwen.studio.agent.manager.entity.MappingEntity;
import com.openjiuwen.studio.agent.manager.entity.ReleaseVersion;
import com.openjiuwen.studio.agent.manager.mapper.AgentMapper;
import com.openjiuwen.studio.agent.manager.mapper.MappingMapper;
import com.openjiuwen.studio.agent.manager.mapper.ReleaseVersionMapper;
import com.openjiuwen.studio.agent.manager.mapper.WorkflowMapper;
import com.openjiuwen.studio.agent.manager.obs.MgObsService;
import com.openjiuwen.studio.agent.manager.workflow.resource.model.RuntimeAssetManifest;
import com.openjiuwen.studio.agent.manager.workflow.resource.model.RuntimeAssetManifest.ManifestAsset;
import com.openjiuwen.studio.agent.manager.workflow.resource.model.RuntimeAssetManifest.ManifestResource;
import com.openjiuwen.studio.agent.manager.workflow.resource.model.RuntimeAssetManifest.ManifestSummary;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 运行时资产导出器（对应 SDD 4.2）。
 * <p>从 t_mapping 依赖图收集资产清单，从 OBS 读取文件，按 OBS object key 组织成 ZIP + manifest.json。
 * <p>已实现：版本解析前置（latest→具体版本号）+ t_mapping 递归遍历（不过滤共享资源）+
 * 根 IR/子 IR/model-service/model-auth/KB 连接+引用/skill/metadata 收集 + 平台模型特判 +
 * STRATEGY 递归（service_id_list + auth_id_list）+ KB reranker/model 模型递归 +
 * 子 IR 版本以父 IR 内嵌 ir_path 为准（含 SubWorkflow components[].configs.reference.path）+
 * authId 从 IR 5 处提取 + 流式 ZIP 打包（二进制技能走 readObsFileStream，逐资产不常驻内存）+
 * manifest + SHA256 + 根 IR/metadata 缺失终止 + resourceId 归属校验（防越权 IDOR）+ assets 上限 + 并发信号量。
 * <p>留增量补全：资产预览接口（preview，PRD 5.2/SDD 4.3.2）。
 */
@Slf4j
@Component
public class RuntimeAssetExporter {

    @Autowired
    private MappingMapper mappingMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private WorkflowMapper workflowMapper;

    @Autowired
    private MgObsService mgObsService;

    @Autowired
    private ReleaseVersionMapper releaseVersionMapper;

    @Autowired
    private PublishUtils publishUtils;

    /** t_mapping 递归深度上限（防止异常循环引用导致栈溢出） */
    private static final int MAX_MAPPING_DEPTH = 20;

    /** JSON 树递归深度上限（extractAuthIds 遍历用户构建的 IR，防深嵌套栈溢出，对齐 MAX_MAPPING_DEPTH 防护思路） */
    private static final int MAX_JSON_DEPTH = 50;

    /** assets 数量上限（SDD 9.1：超限报错提示，防大型智能体导出超时） */
    @org.springframework.beans.factory.annotation.Value("${export.runtime-asset.max-assets:100}")
    private int maxAssets;

    /** 并发导出上限（SDD 4.5：Semaphore 信号量，快速失败） */
    @org.springframework.beans.factory.annotation.Value("${export.runtime-asset.max-concurrent:5}")
    private int maxConcurrent;

    /** manifest source.environment 来源：spring.profiles.active（可能逗号分隔多 profile，取首个），缺省 dev */
    @org.springframework.beans.factory.annotation.Value("${spring.profiles.active:dev}")
    private String springProfile;

    private java.util.concurrent.Semaphore exportSemaphore;

    @javax.annotation.PostConstruct
    private void initSemaphore() {
        // 兜底：maxConcurrent 配 0 或负数时 Semaphore 永远拿不到许可，会导致导出永久 busy。
        this.exportSemaphore = new java.util.concurrent.Semaphore(Math.max(1, maxConcurrent));
    }

    /** 资产类型常量（manifest 中 type 字段取值） */
    private static final String TYPE_AGENT_IR = "agent_ir";
    private static final String TYPE_WORKFLOW_IR = "workflow_ir";
    private static final String TYPE_MODEL_SERVICE = "model_service";
    private static final String TYPE_MODEL_AUTH = "model_auth";
    private static final String TYPE_KB_CONNECTION = "kb_connection";
    private static final String TYPE_KB_REFERENCE = "kb_reference";
    private static final String TYPE_METADATA = "metadata";
    private static final String TYPE_SKILL = "skill";

    /** 平台模型 project_id 取值（runtime PLATFORM_PROJECT_IDS = {"SYSTEM"}） */
    private static final String PLATFORM_SYSTEM = "SYSTEM";

    /** t_mapping resourceType 常量 */
    private static final String RES_TYPE_WORKFLOW = "workflow";
    private static final String RES_TYPE_AGENT = "agent";
    private static final String RES_TYPE_CONTROLLER = "controller";
    private static final String RES_TYPE_MODEL = "model";
    private static final String RES_TYPE_STRATEGY = "strategy";
    private static final String RES_TYPE_REPO = "repo";
    private static final String RES_TYPE_SKILL = "skill";

    /**
     * 导出运行时资产，返回 ZIP 字节流（供前端直接下载，不走 staging bucket）。
     * 复用 doExportRuntimeAssetZip 的收集+打包逻辑，直接返回 ZIP 字节。
     *
     * @param projectId    请求显式 projectId（与 RequestContext 校验一致性，防越权）
     * @param workspaceId  请求显式 workspaceId（与 RequestContext 校验一致性，防越权）
     */
    public byte[] exportRuntimeAssetZip(String projectId, String workspaceId,
        String resourceType, String resourceId, String version) {
        // 越权校验：显式 projectId/workspaceId 必须与 RequestContext（拦截器从鉴权注入）一致
        String ctxProjectId = RequestContextUtils.getRequestProjectId();
        String ctxWorkspaceId = RequestContextUtils.getRequestWorkspaceId();
        if (StringUtils.isNotBlank(ctxProjectId) && !ctxProjectId.equals(projectId)) {
            log.error("runtime_asset_export_forbidden|request_project_id={}|ctx_project_id={}", projectId, ctxProjectId);
            throw new AgentStudioException(StudioError.USER_WORKSPACE_PERMISSION_INVALID,
                "project_id does not match the authenticated context");
        }
        if (StringUtils.isNotBlank(ctxWorkspaceId) && !ctxWorkspaceId.equals(workspaceId)) {
            log.error("runtime_asset_export_forbidden|request_workspace_id={}|ctx_workspace_id={}",
                workspaceId, ctxWorkspaceId);
            throw new AgentStudioException(StudioError.USER_WORKSPACE_PERMISSION_INVALID,
                "workspace_id does not match the authenticated context");
        }
        // 越权校验：resourceId 必须归属当前 (projectId, workspaceId)。
        // 复用现有编辑态导出的归属校验（AgentExportService.validExportWorkflows / AgentMapper.selectByProjectIdAndWorkspaceId），
        // 否则用户可传他人 workspace 的 resourceId 越权导出其 IR+模型鉴权+KB+技能（OBS 路径与 t_mapping 均不带 workspace 过滤）。
        validateResourceOwnership(projectId, workspaceId, resourceType, resourceId);
        if (!exportSemaphore.tryAcquire()) {
            throw new AgentStudioException(StudioError.EXPORT_RESOURCE_COMMON_ERROR,
                "export task busy, try later");
        }
        try {
            return doExportRuntimeAssetZip(resourceType, resourceId, version);
        } finally {
            exportSemaphore.release();
        }
    }

    /**
     * 校验 resourceId 归属当前 (projectId, workspaceId)，防越权导出他人资产（SDD 安全/巡检 #2）。
     * 复用现有编辑态导出的归属校验语义：
     * <ul>
     *   <li>agent/controller → {@link AgentMapper#selectByProjectIdAndWorkspaceId}，null 即无权限</li>
     *   <li>workflow → {@link WorkflowMapper#selectByWorkflowIds}，空即无权限（对齐 AgentExportService.validExportWorkflows）</li>
     * </ul>
     */
    private void validateResourceOwnership(String projectId, String workspaceId, String resourceType,
        String resourceId) {
        if (RES_TYPE_WORKFLOW.equals(resourceType)) {
            List<com.openjiuwen.studio.agent.manager.entity.WorkflowEntity> workflows =
                workflowMapper.selectByWorkflowIds(projectId, workspaceId, List.of(resourceId));
            if (workflows == null || workflows.isEmpty()) {
                log.error("runtime_asset_export_forbidden|resource_type={}|resource_id={}|project_id={}|workspace_id={}",
                    resourceType, resourceId, projectId, workspaceId);
                throw new AgentStudioException(StudioError.WORKFLOW_NO_PERMISSION, resourceId);
            }
            return;
        }
        // agent / controller
        if (agentMapper.selectByProjectIdAndWorkspaceId(projectId, workspaceId, resourceId) == null) {
            log.error("runtime_asset_export_forbidden|resource_type={}|resource_id={}|project_id={}|workspace_id={}",
                resourceType, resourceId, projectId, workspaceId);
            throw new AgentStudioException(StudioError.USER_WORKSPACE_PERMISSION_INVALID,
                "resource does not belong to the authenticated workspace");
        }
    }

    private byte[] doExportRuntimeAssetZip(String resourceType, String resourceId, String version) {
        String projectId = RequestContextUtils.getRequestProjectId();
        String workspaceId = RequestContextUtils.getRequestWorkspaceId();
        String exportId = UUID.randomUUID().toString();
        long startMs = System.currentTimeMillis();
        log.info("runtime_asset_export_start|export_id={}|resource_type={}|resource_id={}|version={}",
            exportId, resourceType, resourceId, version);

        // 0. 版本解析（前置，必须）—— latest → 具体发布版本号
        String resolvedVersion = resolvePublishVersion(resourceType, resourceId, version);
        log.info("runtime_asset_export_resolved_version|export_id={}|resource_id={}|resolved_version={}",
            exportId, resourceId, resolvedVersion);

        // 1. 复用现有依赖遍历（不调 filterShareResource —— 运行态按父 IR 内嵌 ir_path 加载共享子资源）
        List<MappingEntity> allMappings = new ArrayList<>();
        List<MappingEntity> rootMappings =
            mappingMapper.selectByAppIdAndAppVersion(resourceId, resolvedVersion, null, null);
        collectMappingsRecursive(allMappings, rootMappings);

        // 2. 构建 OBS object key 清单（根资产按 resourceType 分流）
        Set<String> collectedKeys = new HashSet<>();
        List<ManifestAsset> assets = new ArrayList<>();
        // 单次导出内 OBS 读取缓存：收集阶段（提 skill/authId）与打包阶段会读同一批 IR/model-service，
        // 去重避免 2~3 倍重复 OBS 往返（model-service 在 collectModelAssets/collectModelAuthFromIrs/build 各读一次）。
        Map<String, String> obsCache = new HashMap<>();
        // 2a. 根 IR
        String rootIrKey = buildRootIrKey(resourceType, resourceId, resolvedVersion);
        addAsset(assets, collectedKeys, rootIrKey, rootIrType(resourceType));
        // 2b. 子 IR（workflow/agent/controller）+ model + KB + skill，从 t_mapping 收集
        collectAssetKeysFromMappings(assets, collectedKeys, allMappings, projectId, obsCache);
        // 2c. 发布元数据
        String metadataKey = buildMetadataKey(resourceId);
        addAsset(assets, collectedKeys, metadataKey, TYPE_METADATA);
        // 2d. 从根/子 IR 提取 skill/子IR ir_path 对齐/authId（SDD 5.1/5.2/5.4）
        List<ManifestAsset> irAssets = assets.stream()
            .filter(a -> TYPE_AGENT_IR.equals(a.getType()) || TYPE_WORKFLOW_IR.equals(a.getType()))
            .toList();
        Set<String> irAuthIds = collectIrDerivedAssets(assets, collectedKeys, irAssets, projectId, obsCache);
        // 2e. model-auth：用 IR 提取的 authId 补充 model-auth 资产（SDD 5.2，优先于 model-service.auth_id）
        collectModelAuthFromIrs(assets, collectedKeys, irAssets, irAuthIds, projectId, obsCache);

        // SDD 9.1：assets 数量上限校验（防大型智能体导出超时）
        if (assets.size() > maxAssets) {
            throw new AgentStudioException(StudioError.EXPORT_LENGTH_TOO_LARGE,
                "assets count " + assets.size() + " exceeds max " + maxAssets);
        }

        // 3. 读取 OBS 文件 + 打包 ZIP + 构建 manifest
        RuntimeAssetManifest manifest = buildManifest(exportId, resourceType, resourceId, resolvedVersion,
            projectId, workspaceId, assets);
        boolean isHistoryExport = StringUtils.isNotBlank(version)
            && !Constants.LATEST_PUBLISH_VERSION.equals(version);
        byte[] zipBytes = buildZipWithManifest(manifest, assets, rootIrKey, isHistoryExport, resolvedVersion, obsCache);

        // 直接返回 ZIP 字节流（供前端下载，不走 staging bucket）
        long durationMs = System.currentTimeMillis() - startMs;
        int missingCount = (int) assets.stream().filter(a -> "missing".equals(a.getStatus())).count();
        String status = missingCount == 0 ? "completed" : "completed_with_missing";
        log.info("runtime_asset_export_end|export_id={}|resource_type={}|resource_id={}|status={}"
            + "|asset_count={}|missing_count={}|duration_ms={}|total_size={}",
            exportId, resourceType, resourceId, status, assets.size(), missingCount, durationMs,
            manifest.getSummary().getTotalSizeBytes());
        return zipBytes;
    }

    /**
     * 版本解析（SDD 5.0，前置）：latest → 具体发布版本号。
     * 禁止把 null/"latest" 传给 selectByAppIdAndAppVersion。
     */
    private String resolvePublishVersion(String resourceType, String resourceId, String version) {
        if (StringUtils.isBlank(version) || Constants.LATEST_PUBLISH_VERSION.equals(version)) {
            // SDD 5.0：Redis 优先回退 OBS（PublishUtils.getPublish 已实现该语义）
            com.openjiuwen.studio.agent.common.bo.AgentMetadata meta =
                publishUtils.getPublish(resourceId, Constants.LATEST_PUBLISH_VERSION, mgObsService);
            if (meta == null || StringUtils.isBlank(meta.getVersionId())) {
                log.error("resolvePublishVersion failed, metadata not found for: {}", resourceId);
                throw new AgentStudioException(StudioError.EXPORT_RESOURCE_NOT_EXISTS, "agent not published");
            }
            return meta.getVersionId();
        }
        // 具体版本号 → 校验存在
        ReleaseVersion rv = releaseVersionMapper.selectByAppIdAndVersionId(resourceId, version);
        if (rv == null) {
            throw new AgentStudioException(StudioError.EXPORT_RESOURCE_NOT_EXISTS, "release version not exist");
        }
        return version;
    }

    /**
     * 递归收集 t_mapping（复用 buildSubExportResources 语义，不过滤共享资源）。
     * SDD 5.1：不调 filterShareResource，运行态按父 IR 内嵌 ir_path 加载共享子资源。
     * <p>去重：同一 (resourceId, resourceType, resourceVersion) 只处理一次，防止循环引用无限递归。
     */
    private void collectMappingsRecursive(List<MappingEntity> acc, List<MappingEntity> mappings) {
        collectMappingsRecursive(acc, mappings, new HashSet<>(), 0);
    }

    private void collectMappingsRecursive(List<MappingEntity> acc, List<MappingEntity> mappings,
        Set<String> visited, int depth) {
        if (mappings == null || mappings.isEmpty() || depth > MAX_MAPPING_DEPTH) {
            return;
        }
        List<MappingEntity> nextLevel = new ArrayList<>();
        for (MappingEntity m : mappings) {
            // 去重：同一资源（resourceId+resourceType+resourceVersion）只处理一次
            String key = mappingKey(m);
            if (!visited.add(key)) {
                continue;
            }
            acc.add(m);
            // 递归查子工作流/子智能体的 mapping
            if (RES_TYPE_WORKFLOW.equals(m.getResourceType()) || RES_TYPE_AGENT.equals(m.getResourceType())
                || RES_TYPE_CONTROLLER.equals(m.getResourceType())) {
                List<MappingEntity> childMappings =
                    mappingMapper.selectByAppIdAndAppVersion(m.getResourceId(), m.getResourceVersion(), null, null);
                if (childMappings != null && !childMappings.isEmpty()) {
                    nextLevel.addAll(childMappings);
                }
            }
        }
        if (!nextLevel.isEmpty()) {
            collectMappingsRecursive(acc, nextLevel, visited, depth + 1);
        }
    }

    private static String mappingKey(MappingEntity m) {
        return (m.getResourceId() == null ? "" : m.getResourceId()) + "|"
            + (m.getResourceType() == null ? "" : m.getResourceType()) + "|"
            + (m.getResourceVersion() == null ? "" : m.getResourceVersion());
    }

    /**
     * 从 t_mapping 收集资产 key（SDD 4.2.4）：子 IR + model-service/model-auth + KB 连接/引用 + skill 交叉校验。
     */
    private void collectAssetKeysFromMappings(List<ManifestAsset> assets, Set<String> collectedKeys,
        List<MappingEntity> mappings, String rootProjectId, Map<String, String> obsCache) {
        for (MappingEntity m : mappings) {
            String resType = m.getResourceType();
            String resId = m.getResourceId();
            String resVer = m.getResourceVersion();
            switch (resType) {
                case RES_TYPE_WORKFLOW:
                    addAsset(assets, collectedKeys, buildWorkflowIrKey(resId, resVer), TYPE_WORKFLOW_IR);
                    break;
                case RES_TYPE_AGENT:
                case RES_TYPE_CONTROLLER:
                    addAsset(assets, collectedKeys, buildAgentIrKey(resId, resVer), TYPE_AGENT_IR);
                    break;
                case RES_TYPE_MODEL:
                case RES_TYPE_STRATEGY:
                    // model-service 配置 + model-auth 鉴权配置（SDD 5.2）
                    collectModelAssets(assets, collectedKeys, resId, resType, rootProjectId, obsCache, null);
                    break;
                case RES_TYPE_REPO:
                    // KB 引用 + KB 连接（SDD 5.3）
                    collectKbAssets(assets, collectedKeys, resId, rootProjectId, obsCache);
                    break;
                case RES_TYPE_SKILL:
                    // skill 路径以 IR skill_info[].skill_path 为准，t_mapping 仅交叉校验（SDD 5.4）
                    // 在 collectSkillAssets 中从根/子 IR 提取
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 从根/子 IR 提取 skill 资产 + 子 IR ir_path 对齐 + authId 提取（SDD 5.1/5.2/5.4）。
     * <p>遍历已收集的 agent_ir/workflow_ir 资产，读 IR JSON：
     * <ul>
     *   <li>SDD 5.4：取 configs.skills.skill_info[].skill_path（skill 资产）</li>
     *   <li>SDD 5.1：取 configs.workflows[].ir_path / configs.agents[].ir_path
     *       （子资产版本以父 IR 内嵌 ir_path 为准，t_mapping 版本仅交叉校验）</li>
     *   <li>SDD 5.2：递归提取所有 extension.authId（5 处：modelConfig/工作流model/LLM节点/Questioner/意图检测）</li>
     * </ul>
     * @return 提取到的 authId 集合（供 model-auth 收集使用）
     */
    private Set<String> collectIrDerivedAssets(List<ManifestAsset> assets, Set<String> collectedKeys,
        List<ManifestAsset> irAssets, String rootProjectId, Map<String, String> obsCache) {
        Set<String> authIds = new HashSet<>();
        Set<String> childIrPaths = new HashSet<>();
        // resourceId → 已收集的 IR asset（用于子 IR 版本冲突检测：t_mapping 版本 vs 父 IR 内嵌 ir_path 版本）
        Map<String, ManifestAsset> irByResourceId = new HashMap<>();
        for (ManifestAsset existing : irAssets) {
            String rid = parseResourceIdFromIrKey(existing.getKey());
            if (rid != null) {
                irByResourceId.put(rid, existing);
            }
        }
        for (ManifestAsset asset : irAssets) {
            String irJson;
            try {
                irJson = getCachedObject(asset.getKey(), obsCache);
            } catch (Exception e) {
                continue;
            }
            if (StringUtils.isBlank(irJson)) {
                continue;
            }
            try {
                JSONObject ir = JSON.parseObject(irJson);
                JSONObject configs = ir.getJSONObject("configs");
                if (configs == null) {
                    continue;
                }
                // SDD 5.4：skill_info[].skill_path
                JSONObject skills = configs.getJSONObject("skills");
                if (skills != null) {
                    JSONArray skillInfo = skills.getJSONArray("skill_info");
                    if (skillInfo != null) {
                        for (int i = 0; i < skillInfo.size(); i++) {
                            JSONObject skill = skillInfo.getJSONObject(i);
                            String skillPath = skill.getString("skill_path");
                            if (StringUtils.isNotBlank(skillPath)) {
                                addAsset(assets, collectedKeys, skillPath, TYPE_SKILL);
                            }
                        }
                    }
                }
                // SDD 5.1：configs.workflows[].ir_path / configs.agents[].ir_path（子资产版本对齐）
                collectChildIrPaths(configs, "workflows", childIrPaths);
                collectChildIrPaths(configs, "agents", childIrPaths);
                // SDD 2.5/5.1：SubWorkflow 节点子工作流路径 components[].configs.reference.path
                collectSubWorkflowPaths(ir, childIrPaths);
                // SDD 5.2：递归提取所有 extension.authId（5 处）
                extractAuthIds(ir, authIds);
            } catch (Exception e) {
                log.warn("collectIrDerivedAssets parse IR failed, key={}. {}", asset.getKey(), e.getMessage());
            }
        }
        // SDD 5.1：子资产版本以父 IR 内嵌 ir_path 为准。t_mapping 版本与 ir_path 版本冲突时，
        // ir_path 胜出（运行态按父 IR 内嵌 ir_path 加载子资产），并在 manifest note 记录差异（巡检 #3/#4）。
        for (String irPath : childIrPaths) {
            String type = irPath.startsWith("workflow/") ? TYPE_WORKFLOW_IR : TYPE_AGENT_IR;
            String rid = parseResourceIdFromIrKey(irPath);
            ManifestAsset existing = (rid != null) ? irByResourceId.get(rid) : null;
            if (existing != null) {
                if (existing.getKey().equals(irPath)) {
                    continue; // 同 key 已收集，去重
                }
                // 版本冲突：ir_path 为准，移除 t_mapping 版本，加入 ir_path 版本
                String oldVer = parseVersionFromIrKey(existing.getKey());
                assets.remove(existing);
                collectedKeys.remove(existing.getKey());
                ManifestAsset added = addAsset(assets, collectedKeys, irPath, type);
                if (added != null) {
                    added.setNote("version aligned to parent IR ir_path; t_mapping version=" + oldVer);
                    if (rid != null) {
                        irByResourceId.put(rid, added);
                    }
                }
                log.warn("runtime_asset_export_version_mismatch|resource_id={}|mapping_version={}|ir_path={}",
                    rid, oldVer, irPath);
            } else {
                ManifestAsset added = addAsset(assets, collectedKeys, irPath, type);
                if (added != null && rid != null) {
                    irByResourceId.put(rid, added);
                }
            }
        }
        return authIds;
    }

    /** 从 configs.workflows / configs.agents 数组提取 ir_path */
    private void collectChildIrPaths(JSONObject configs, String key, Set<String> childIrPaths) {
        JSONArray arr = configs.getJSONArray(key);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String irPath = item.getString("ir_path");
            if (StringUtils.isNotBlank(irPath)) {
                childIrPaths.add(irPath);
            }
        }
    }

    /**
     * 从 components[].configs.reference.path 提取 SubWorkflow 子工作流 IR 路径（SDD 2.5/5.1）。
     * SubWorkflow 节点的子工作流引用不在 configs.workflows/agents 数组里，而在 components[].configs.reference.path。
     */
    private void collectSubWorkflowPaths(JSONObject ir, Set<String> childIrPaths) {
        JSONArray components = ir.getJSONArray("components");
        if (components == null) {
            return;
        }
        for (int i = 0; i < components.size(); i++) {
            JSONObject component = components.getJSONObject(i);
            if (component == null) {
                continue;
            }
            JSONObject configs = component.getJSONObject("configs");
            if (configs == null) {
                continue;
            }
            JSONObject reference = configs.getJSONObject("reference");
            if (reference != null) {
                String irPath = reference.getString("path");
                if (StringUtils.isNotBlank(irPath)) {
                    childIrPaths.add(irPath);
                }
            }
        }
    }

    /**
     * 递归提取 IR 中所有 extension.authId（SDD 5.2，5 处位置）。
     * 策略：递归遍历 JSON 树，对每个含 "extension" 的对象，取 extension.authId。
     * 覆盖 modelConfig/工作流model/LLM节点/Questioner/意图检测等所有含 modelName+extension 的位置，
     * 不逐个硬编码路径（防新增节点类型遗漏）。
     */
    private void extractAuthIds(Object node, Set<String> authIds) {
        extractAuthIds(node, authIds, 0);
    }

    private void extractAuthIds(Object node, Set<String> authIds, int depth) {
        if (node == null || depth > MAX_JSON_DEPTH) {
            return;
        }
        if (node instanceof JSONObject obj) {
            JSONObject ext = obj.getJSONObject("extension");
            if (ext != null) {
                String authId = ext.getString("authId");
                if (StringUtils.isNotBlank(authId)) {
                    authIds.add(authId);
                }
            }
            for (String k : obj.keySet()) {
                extractAuthIds(obj.get(k), authIds, depth + 1);
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                extractAuthIds(arr.get(i), authIds, depth + 1);
            }
        }
    }

    /**
     * 用 IR 提取的 authId 补充 model-auth 资产（SDD 5.2，作为 collectModelAssets 的防御性补充）。
     * <p>对全部 IR authId × 全部已收集 model-service 的 (providerId, projectId) 做笛卡尔积构建 auth key。
     * 这是"宁可多导不漏导"的取舍，正确性不受影响：
     * <ul>
     *   <li>正确组合（IR authId + 其所属 model-service 的 provider/project）必然在笛卡尔积中 → 不漏；
     *       且 collectModelAssets 已按 model-service.auth_metadata_id 构建过同一正确 key（collectedKeys 去重）。</li>
     *   <li>错误组合的 key 要么 OBS 不存在（标 missing、不入包），要么是别的模型的 auth 文件（运行态不会按此 key 加载），
     *       仅产生 manifest 噪声，无错配风险。</li>
     * </ul>
     * 不做精确 (modelName→model-service→provider) 映射：IR 中 extension.authId 与 modelName 的同级关系
     * 在 5 处位置命名不一（modelName/model_name），精确提取有漏导 authId 风险（运行态缺 auth 会调用失败），
     * 而"多导"仅是噪声——故保持笛卡尔。
     */
    private void collectModelAuthFromIrs(List<ManifestAsset> assets, Set<String> collectedKeys,
        List<ManifestAsset> irAssets, Set<String> irAuthIds, String rootProjectId, Map<String, String> obsCache) {
        if (irAuthIds.isEmpty()) {
            return;
        }
        // 收集所有已收集的 model-service 资产,读其 providerId/projectId
        List<ManifestAsset> modelServiceAssets = assets.stream()
            .filter(a -> TYPE_MODEL_SERVICE.equals(a.getType())).toList();
        for (ManifestAsset msAsset : modelServiceAssets) {
            String msJson;
            try {
                msJson = getCachedObject(msAsset.getKey(), obsCache);
            } catch (Exception e) {
                continue;
            }
            if (StringUtils.isBlank(msJson)) {
                continue;
            }
            JSONObject msObj = JSON.parseObject(msJson);
            // model-service 文件结构：{"type":"model", "data": {...}}
            JSONObject msData = msObj.getJSONObject("data");
            if (msData == null) {
                msData = msObj;
            }
            String providerId = msData.getString("provider_id");
            String modelProjectId = msData.getString("project_id");
            String authProjectId = (modelProjectId != null && PLATFORM_SYSTEM.equals(modelProjectId))
                ? rootProjectId : modelProjectId;
            // 对每个 IR 提取的 authId,构建 model-auth key(笛卡尔积,去重保证不遗漏)
            for (String authId : irAuthIds) {
                addModelAuthAsset(assets, collectedKeys, authProjectId, providerId, authId);
            }
        }
    }

    /**
     * 收集模型资产：model-service 配置 + model-auth 鉴权配置（SDD 5.2）。
     * <p>1. 读 model-service/ir/{modelServiceId}.json 取 providerId/projectId
     * <p>2. 平台模型特判：project_id=SYSTEM → authProjectId 用根应用 projectId（复刻 runtime _auth_project_id）
     * <p>3. authId 选取：explicitAuthId（STRATEGY auth_id_list 指定）优先，否则回退 model-service.auth_metadata_id
     * <p>4. STRATEGY 路由策略递归：读 service_id_list + auth_id_list（一一对应）逐子模型处理
     * <p>5. authId 空值回退：list 前缀下全部对象导出
     * <p>6. modelName 格式 deploymentId|serviceId 取第一段
     *
     * @param explicitAuthId 调用方指定的 authId（STRATEGY auth_id_list 逐子模型传入）；null 时回退 auth_metadata_id
     */
    private void collectModelAssets(List<ManifestAsset> assets, Set<String> collectedKeys,
        String modelServiceId, String resType, String rootProjectId, Map<String, String> obsCache,
        String explicitAuthId) {
        // modelServiceId 格式处理：deploymentId|serviceId 取第一段
        String serviceId = modelServiceId;
        if (modelServiceId != null && modelServiceId.contains("|")) {
            serviceId = modelServiceId.split("\\|")[0];
        }
        String modelServiceKey = buildModelServiceKey(serviceId);
        if (!collectedKeys.contains(modelServiceKey)) {
            addAsset(assets, collectedKeys, modelServiceKey, TYPE_MODEL_SERVICE);
        }
        // 读 model-service 文件取 providerId/projectId
        String msJson;
        try {
            msJson = getCachedObject(modelServiceKey, obsCache);
        } catch (Exception e) {
            log.warn("collectModelAssets read model-service failed, key={}, skip model-auth. {}",
                modelServiceKey, e.getMessage());
            return;
        }
        if (StringUtils.isBlank(msJson)) {
            return;
        }
        JSONObject msObj = JSON.parseObject(msJson);
        // model-service 文件结构：{"type":"model", "data": {provider_id, project_id, auth_metadata_id, ...}}
        // 字段在 data 嵌套对象里，非顶层
        JSONObject msData = msObj.getJSONObject("data");
        if (msData == null) {
            // 兼容：部分文件可能直接顶层存字段
            msData = msObj;
        }
        String providerId = msData.getString("provider_id");
        String modelProjectId = msData.getString("project_id");
        // 平台模型特判：project_id=SYSTEM → authProjectId 用根应用 projectId
        String authProjectId = (modelProjectId != null && PLATFORM_SYSTEM.equals(modelProjectId))
            ? rootProjectId : modelProjectId;

        if (RES_TYPE_STRATEGY.equals(resType)) {
            // SDD 5.2 STRATEGY 路由策略递归：service_id_list 与 auth_id_list 一一对应（auth_id_list 可为空/较短），
            // 逐子模型递归收集，并把对应 auth_id_list[i] 作为 explicitAuthId 传入（runtime _build_router_strategy 逐子模型 fail-fast）。
            String serviceIdList = msData.getString("service_id_list");
            String authIdList = msData.getString("auth_id_list");
            if (StringUtils.isNotBlank(serviceIdList)) {
                String[] svcIds = serviceIdList.split(",");
                String[] authIds = StringUtils.isNotBlank(authIdList) ? authIdList.split(",") : new String[0];
                for (int i = 0; i < svcIds.length; i++) {
                    String subServiceId = svcIds[i].trim();
                    if (StringUtils.isBlank(subServiceId)) {
                        continue;
                    }
                    String subAuthId = (i < authIds.length) ? authIds[i].trim() : null;
                    if (StringUtils.isBlank(subAuthId)) {
                        subAuthId = null;
                    }
                    collectModelAssets(assets, collectedKeys, subServiceId, RES_TYPE_MODEL, rootProjectId, obsCache,
                        subAuthId);
                }
            }
            return;
        }
        // authId 选取：explicitAuthId（STRATEGY auth_id_list 指定）优先，否则回退 model-service.auth_metadata_id。
        // IR 中 extension.authId 由 collectModelAuthFromIrs 笛卡尔补充（宁可多导不漏导）。
        String authId = StringUtils.isNotBlank(explicitAuthId) ? explicitAuthId : msData.getString("auth_metadata_id");
        addModelAuthAsset(assets, collectedKeys, authProjectId, providerId, authId);
    }

    /**
     * 添加 model-auth 资产。authId 为空时（V1 回退）list 前缀下全部对象导出。
     */
    private void addModelAuthAsset(List<ManifestAsset> assets, Set<String> collectedKeys,
        String authProjectId, String providerId, String authId) {
        if (StringUtils.isAnyBlank(authProjectId, providerId)) {
            return;
        }
        if (StringUtils.isBlank(authId)) {
            // SDD 5.2 V1 回退：authId 空时 list 前缀下全部 auth 对象导出
            String prefix = String.format("model-auth/auth/%s/%s/", authProjectId, providerId);
            log.info("model-auth authId empty, V1 fallback listing prefix: {}", prefix);
            try {
                List<String> authKeys = mgObsService.listObsObjectKeys(prefix);
                if (authKeys != null) {
                    for (String authKey : authKeys) {
                        if (StringUtils.isNotBlank(authKey)) {
                            addAsset(assets, collectedKeys, authKey, TYPE_MODEL_AUTH);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("model-auth V1 fallback list failed, prefix={}. {}", prefix, e.getMessage());
            }
            return;
        }
        String authKey = String.format("model-auth/auth/%s/%s/%s.json", authProjectId, providerId, authId);
        addAsset(assets, collectedKeys, authKey, TYPE_MODEL_AUTH);
    }

    /**
     * 收集知识库资产：KB 引用 + KB 连接（SDD 5.3）。
     * <p>1. 导出 kb-connection/ir/knowledge-base/{kbId}.json
     * <p>2. 从该文件读 connectionId，导出 kb-connection/ir/connection/{connId}.json
     */
    private void collectKbAssets(List<ManifestAsset> assets, Set<String> collectedKeys, String kbId,
        String rootProjectId, Map<String, String> obsCache) {
        String kbRefKey = buildKbReferenceKey(kbId);
        if (!collectedKeys.contains(kbRefKey)) {
            addAsset(assets, collectedKeys, kbRefKey, TYPE_KB_REFERENCE);
        }
        // 读 KB 引用文件取 connectionId
        String kbJson;
        try {
            kbJson = getCachedObject(kbRefKey, obsCache);
        } catch (Exception e) {
            log.warn("collectKbAssets read kb-reference failed, key={}, skip kb-connection. {}",
                kbRefKey, e.getMessage());
            return;
        }
        if (StringUtils.isBlank(kbJson)) {
            return;
        }
        JSONObject kbObj = JSON.parseObject(kbJson);
        String connectionId = kbObj.getString("connectionId");
        if (StringUtils.isNotBlank(connectionId)) {
            String connKey = String.format("kb-connection/ir/connection/%s.json", connectionId);
            addAsset(assets, collectedKeys, connKey, TYPE_KB_CONNECTION);
            // SDD 5.3：KB 连接配置可能含 reranker_config/model_config（引用模型），递归收集模型
            collectKbReferencedModels(assets, collectedKeys, connectionId, rootProjectId, obsCache);
        }
    }

    /**
     * KB 连接配置中 reranker_config/model_config 引用的模型，递归收集 model-service + model-auth（SDD 5.3，巡检 #5）。
     * <p>连接配置结构：{@code params[]} 数组，每项 {@code {code, value}}；其中 code=model_config/reranker_config
     * 的 value 是 JSON 字符串，含 {@code model_service_id}（embedding/reranker 模型）。
     * <p>对每个 model_service_id 调 collectModelAssets（explicitAuthId=null，authId 取 model-service.auth_metadata_id）。
     * 防御式：字段缺失/解析失败则 debug 跳过，不阻断导出。
     */
    private void collectKbReferencedModels(List<ManifestAsset> assets, Set<String> collectedKeys,
        String connectionId, String rootProjectId, Map<String, String> obsCache) {
        String connKey = String.format("kb-connection/ir/connection/%s.json", connectionId);
        String connJson;
        try {
            connJson = getCachedObject(connKey, obsCache);
        } catch (Exception e) {
            log.warn("collectKbReferencedModels read connection failed, key={}, skip reranker. {}",
                connKey, e.getMessage());
            return;
        }
        if (StringUtils.isBlank(connJson)) {
            return;
        }
        try {
            JSONObject conn = JSON.parseObject(connJson);
            JSONArray params = conn.getJSONArray("params");
            if (params == null) {
                return;
            }
            for (int i = 0; i < params.size(); i++) {
                JSONObject param = params.getJSONObject(i);
                if (param == null) {
                    continue;
                }
                String code = param.getString("code");
                if (!"model_config".equals(code) && !"reranker_config".equals(code)) {
                    continue;
                }
                String valueJson = param.getString("value");
                if (StringUtils.isBlank(valueJson)) {
                    continue;
                }
                JSONObject mc = JSON.parseObject(valueJson);
                String modelServiceId = mc.getString("model_service_id");
                if (StringUtils.isNotBlank(modelServiceId)) {
                    collectModelAssets(assets, collectedKeys, modelServiceId, RES_TYPE_MODEL, rootProjectId, obsCache, null);
                }
            }
        } catch (Exception e) {
            log.warn("collectKbReferencedModels parse connection failed, connectionId={}. {}", connectionId,
                e.getMessage());
        }
    }

    // ===== object key 构建（对齐 SDD 2.2 路径模板，前缀与 runtime build_*_ir_path 一致）=====

    private String buildRootIrKey(String resourceType, String resourceId, String version) {
        if (RES_TYPE_WORKFLOW.equals(resourceType)) {
            return buildWorkflowIrKey(resourceId, version);
        }
        return buildAgentIrKey(resourceId, version);
    }

    private String buildAgentIrKey(String agentId, String version) {
        return String.format("%s/%s/%s/%s_%s.json", CommonConstant.AGENT, CommonConstant.Workflow.IR,
            agentId, agentId, version);
    }

    private String buildWorkflowIrKey(String workflowId, String version) {
        return String.format("%s/%s/%s/%s_%s.json", CommonConstant.WORKFLOW, CommonConstant.Workflow.IR,
            workflowId, workflowId, version);
    }

    private String buildModelServiceKey(String modelServiceId) {
        return String.format("model-service/ir/%s.json", modelServiceId);
    }

    private String buildKbReferenceKey(String kbId) {
        return String.format("kb-connection/ir/knowledge-base/%s.json", kbId);
    }

    private String buildMetadataKey(String resourceId) {
        return String.format("%s/%s/%s_%s.json", Constants.OBS_METADATA_DIR, resourceId, resourceId,
            Constants.LATEST_PUBLISH_VERSION);
    }

    private String rootIrType(String resourceType) {
        return RES_TYPE_WORKFLOW.equals(resourceType) ? TYPE_WORKFLOW_IR : TYPE_AGENT_IR;
    }

    /**
     * 读取 OBS 对象，按 object key 缓存（单次导出内去重，避免收集阶段与打包阶段重复读同一对象）。
     * <p>异常不缓存：抛出由调用方处理（收集阶段 catch 后 skip/标 missing；打包阶段 catch 后按 critical 终止或标 missing），
     * 下次同一 key 重读，行为与无缓存时一致。
     * <p>含 null 缓存：mgObsService 返回 null 时缓存 null，用 containsKey 区分"已读过且为空"与"未读过"。
     */
    private String getCachedObject(String key, Map<String, String> obsCache) {
        if (obsCache.containsKey(key)) {
            return obsCache.get(key);
        }
        String value = mgObsService.getObject(key);
        obsCache.put(key, value);
        return value;
    }

    /**
     * 添加资产到清单（去重）。critical（根 IR/metadata 缺失终止）由 buildZipWithManifest
     * 通过 isCriticalAsset 判断，此处不标记。
     */
    private ManifestAsset addAsset(List<ManifestAsset> assets, Set<String> collectedKeys, String key, String type) {
        if (StringUtils.isBlank(key) || !collectedKeys.add(key)) {
            return null; // 空或已收集（去重）
        }
        ManifestAsset asset = new ManifestAsset();
        asset.setType(type);
        asset.setKey(key);
        assets.add(asset);
        return asset;
    }

    /**
     * 从 IR object key 解析 resourceId（SDD 5.1 版本冲突检测用）。
     * key 形如 agent/ir/{rid}/{rid}_{ver}.json 或 workflow/ir/{rid}/{rid}_{ver}.json → 返回 {rid}。
     */
    private static String parseResourceIdFromIrKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("/");
        if (parts.length >= 4 && "ir".equals(parts[1])
            && (CommonConstant.AGENT.equals(parts[0]) || CommonConstant.WORKFLOW.equals(parts[0]))) {
            return parts[2];
        }
        return null;
    }

    /** 从 IR object key 解析版本号（note 记录用，无法解析返回 "?"）。 */
    private static String parseVersionFromIrKey(String key) {
        String rid = parseResourceIdFromIrKey(key);
        if (rid == null) {
            return "?";
        }
        String fname = key.substring(key.lastIndexOf('/') + 1);
        if (fname.startsWith(rid + "_") && fname.endsWith(".json")) {
            return fname.substring(rid.length() + 1, fname.length() - 5);
        }
        return "?";
    }

    /**
     * 读取 OBS 资产文件 + 打包 ZIP + 构建 manifest（SDD 4.5/5.6）。
     * <p>内存策略：不将全部资产 byte[] 常驻内存（移除原 dataList 全量缓存）：
     * <ul>
     *   <li>文本资产（IR/model-service/model-auth/KB/metadata）：走 getCachedObject（String，UTF-8），
     *       逐资产 getBytes 写入 zos；metadata 命中历史版本导出时先 rewriteMetadataVersionId。</li>
     *   <li>二进制资产（TYPE_SKILL）：走 {@link MgObsService#readObsFileStream} 字节流，
     *       逐资产缓冲（同一时刻仅一个技能 ZIP 在内存）后写入 zos、MessageDigest 流式计 sha256——
     *       技能 ZIP 经文本通道（getObject/UTF-8 解码）会损坏二进制，必须走字节流（巡检 #1）；
     *       流式读取过程中失败则丢弃缓冲、不写 entry（避免 ZIP 残留半截损坏 entry）。</li>
     * </ul>
     * <p>SDD 5.6：根 IR（rootIrKey）+ metadata 缺失即终止；其他缺失标 missing 继续（不入包）。
     * <p>manifest.json 作为最后一个 entry 写入（summary 在资产循环内累计完成后回填）。
     * @param rootIrKey 根 IR 的 object key（用于区分根 IR vs 子 IR 的缺失处理）
     */
    private byte[] buildZipWithManifest(RuntimeAssetManifest manifest, List<ManifestAsset> assets,
        String rootIrKey, boolean isHistoryExport, String resolvedVersion, Map<String, String> obsCache) {
        int missingCount = 0;
        long totalSize = 0;
        int irCount = 0, modelCount = 0, kbCount = 0, skillCount = 0, metadataCount = 0;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (ManifestAsset asset : assets) {
                // 二进制资产（技能 ZIP）走字节流；其余文本资产走缓存 String
                if (TYPE_SKILL.equals(asset.getType())) {
                    long[] sizeSha = streamBinaryAsset(zos, asset);
                    if (sizeSha == null) {
                        // 读取失败/空：critical 终止（技能非 critical，此处理论上不触发）；否则已标 missing
                        if (isCriticalAsset(asset, rootIrKey)) {
                            throw new AgentStudioException(StudioError.EXPORT_RESOURCE_NOT_EXISTS,
                                "critical asset missing: " + asset.getKey());
                        }
                        missingCount++;
                        continue;
                    }
                    totalSize += sizeSha[0];
                    skillCount++;
                    continue;
                }
                // 文本资产
                String content;
                try {
                    content = getCachedObject(asset.getKey(), obsCache);
                } catch (Exception e) {
                    if (isCriticalAsset(asset, rootIrKey)) {
                        throw new AgentStudioException(StudioError.EXPORT_RESOURCE_NOT_EXISTS,
                            "critical asset missing: " + asset.getKey());
                    }
                    log.warn("runtime_asset_export_asset_missing|key={}|type={}|reason={}",
                        asset.getKey(), asset.getType(), e.getMessage());
                    asset.setStatus("missing");
                    asset.setErrorReason(e.getMessage());
                    asset.setSha256(null);
                    missingCount++;
                    continue;
                }
                if (StringUtils.isBlank(content)) {
                    if (isCriticalAsset(asset, rootIrKey)) {
                        throw new AgentStudioException(StudioError.EXPORT_RESOURCE_NOT_EXISTS,
                            "critical asset empty: " + asset.getKey());
                    }
                    asset.setStatus("missing");
                    asset.setErrorReason("OBS object empty");
                    missingCount++;
                    continue;
                }
                // SDD 5.5：导出历史版本时，_latest.json 内容仍指向当前最新版本，
                // 运行态按 _latest.json 加载会拉错版本。导出前改写 versionId 为 resolvedVersion。
                if (isHistoryExport && TYPE_METADATA.equals(asset.getType())) {
                    content = rewriteMetadataVersionId(content, resolvedVersion);
                }
                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                String sha = sha256Hex(data);
                asset.setSize(data.length);
                asset.setSha256(sha);
                asset.setStatus("ok");
                zos.putNextEntry(new ZipEntry(asset.getKey()));
                zos.write(data);
                zos.closeEntry();
                totalSize += data.length;
                switch (asset.getType()) {
                    case TYPE_AGENT_IR:
                    case TYPE_WORKFLOW_IR:
                        irCount++;
                        break;
                    case TYPE_MODEL_SERVICE:
                    case TYPE_MODEL_AUTH:
                        modelCount++;
                        break;
                    case TYPE_KB_CONNECTION:
                    case TYPE_KB_REFERENCE:
                        kbCount++;
                        break;
                    case TYPE_METADATA:
                        metadataCount++;
                        break;
                    default:
                        break;
                }
            }
            // 回填 summary（manifest 作为最后 entry，summary 须在序列化前完整）
            ManifestSummary summary = manifest.getSummary();
            summary.setTotalAssets(assets.size());
            summary.setTotalSizeBytes(totalSize);
            summary.setIrCount(irCount);
            summary.setModelCount(modelCount);
            summary.setKbCount(kbCount);
            summary.setSkillCount(skillCount);
            summary.setMetadataCount(metadataCount);
            summary.setMissingCount(missingCount);

            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(JSON.toJSONString(manifest).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            return baos.toByteArray();
        } catch (AgentStudioException e) {
            throw e;
        } catch (Exception e) {
            log.error("buildZipWithManifest error", e);
            throw new AgentStudioException(StudioError.WORKFLOW_EXPORT_FILE);
        }
    }

    /**
     * 读取二进制资产（技能 ZIP）并写入 ZIP entry（巡检 #1）。
     * <p>技能经文本通道 getObject 会 UTF-8 解码损坏二进制，故必须走 readObsFileStream 字节流。
     * <p>内存策略：逐资产缓冲（同一时刻仅一个技能 ZIP 在内存），不将全部资产常驻——
     * 对齐 SDD 4.5"避免 OOM"意图（原 dataList 把全部资产一次性载入，已移除）。
     * <p>正确性：流式读取过程中若失败，已缓冲字节丢弃、不写 entry（避免 ZIP 残留半截损坏 entry
     * 而 manifest 标 missing 的不一致）；仅在完整读取成功后写 entry。
     * @return {size}（成功），null（失败/空，已标 missing，调用方计数）
     */
    private long[] streamBinaryAsset(ZipOutputStream zos, ManifestAsset asset) {
        java.io.InputStream is = null;
        try {
            is = mgObsService.readObsFileStream(asset.getKey());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) {
                buf.write(buffer, 0, n);
                md.update(buffer, 0, n);
            }
            byte[] data = buf.toByteArray();
            if (data.length == 0) {
                asset.setStatus("missing");
                asset.setErrorReason("OBS object empty");
                asset.setSha256(null);
                return null;
            }
            zos.putNextEntry(new ZipEntry(asset.getKey()));
            zos.write(data);
            zos.closeEntry();
            asset.setSize(data.length);
            asset.setSha256(hexDigest(md));
            asset.setStatus("ok");
            return new long[] {data.length};
        } catch (Exception e) {
            log.warn("runtime_asset_export_asset_missing|key={}|type={}|reason={}",
                asset.getKey(), asset.getType(), e.getMessage());
            asset.setStatus("missing");
            asset.setErrorReason(e.getMessage());
            asset.setSha256(null);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignore) {
                    // 关流失败不影响导出
                }
            }
        }
    }

    private String hexDigest(MessageDigest md) {
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 判断资产是否为 critical（缺失即终止）。SDD 5.6：根 IR + metadata 缺失终止。
     * 子 IR（非根的 agent_ir/workflow_ir）缺失标 missing 继续。
     */
    private boolean isCriticalAsset(ManifestAsset asset, String rootIrKey) {
        if (TYPE_METADATA.equals(asset.getType())) {
            return true;
        }
        // 根 IR（key 与 rootIrKey 相同）；子 IR 不终止
        return asset.getKey().equals(rootIrKey);
    }

    private RuntimeAssetManifest buildManifest(String exportId, String resourceType, String resourceId,
        String resolvedVersion, String projectId, String workspaceId, List<ManifestAsset> assets) {
        RuntimeAssetManifest manifest = new RuntimeAssetManifest();
        manifest.setManifestVersion("1.0");
        manifest.setExportId(exportId);
        manifest.setExportedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        manifest.setExportedBy(RequestContextUtils.getRequestUserId());

        RuntimeAssetManifest.ManifestSource source = new RuntimeAssetManifest.ManifestSource();
        // spring.profiles.active 可能是逗号分隔多 profile，取首个非空段；空则兜底 dev
        String env = StringUtils.isNotBlank(springProfile)
            ? springProfile.split(",")[0].trim() : "dev";
        source.setEnvironment(env);
        source.setStorageType("OBS");
        manifest.setSource(source);

        // 根资源记录（version 为 resolvedVersion，即具体发布版本号，非 latest）
        ManifestResource res = new ManifestResource();
        res.setResourceId(resourceId);
        res.setResourceType(resourceType);
        res.setIrPath(buildRootIrKey(resourceType, resourceId, resolvedVersion));
        res.setVersion(resolvedVersion);
        res.setProjectId(projectId);
        res.setWorkspaceId(workspaceId);
        manifest.setResources(List.of(res));

        manifest.setAssets(assets);
        manifest.setSummary(new ManifestSummary());
        return manifest;
    }

    /**
     * 元数据历史版本重写（SDD 5.5）：把 _latest.json 的 versionId 改为 resolvedVersion，
     * 使运行态按 _latest.json 加载到正确的导出版本 IR。updatedAt 同步更新为导出时间戳。
     * 仅发生在导出包内，不回写开发态 OBS。
     */
    private String rewriteMetadataVersionId(String metadataJson, String resolvedVersion) {
        try {
            JSONObject meta = JSON.parseObject(metadataJson);
            meta.put("versionId", resolvedVersion);
            meta.put("updatedAt", System.currentTimeMillis());
            return JSON.toJSONString(meta);
        } catch (Exception e) {
            log.warn("rewriteMetadataVersionId failed, keep original. {}", e.getMessage());
            return metadataJson;
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
