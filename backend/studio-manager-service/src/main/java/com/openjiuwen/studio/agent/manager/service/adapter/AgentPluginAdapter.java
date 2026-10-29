/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.studio.agent.manager.service.adapter;

import com.alibaba.fastjson2.JSONObject;
import com.openjiuwen.studio.agent.manager.dto.AgentInfo;
import com.openjiuwen.studio.agent.manager.dto.ToolReference;
import com.openjiuwen.studio.agent.manager.entity.Agent;
import com.openjiuwen.studio.agent.manager.enums.ResourceTypeEnum;
import com.openjiuwen.studio.agent.manager.mapper.AgentMapper;
import com.openjiuwen.studio.agent.manager.obs.MgObsService;
import com.openjiuwen.studio.agent.manager.service.plugin.IPluginBase;
import com.openjiuwen.studio.agent.manager.workflow.resource.model.ExportResourceUnit;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AgentPluginAdapter {
    @Autowired
    private IPluginBase pluginBaseImpl;

    @Autowired
    private MgObsService obsService;

    @Autowired
    private AgentMapper agentMapper;

    public String getToolVersion(String pluginId, String toolVersion) {
        if (StringUtils.isNotEmpty(toolVersion)) {
            return toolVersion;
        }
        String lastVersion = pluginBaseImpl.getLastVersion(pluginId);
        if (StringUtils.isNotEmpty(lastVersion)) {
            return lastVersion;
        }
        return toolVersion;
    }

    public String getToolVersionFromAgent(ExportResourceUnit exportResourceUnit) {
        // 如果资源版本不为空，直接返回
        String resourceVersion = exportResourceUnit.getResourceVersion();
        if (StringUtils.isNotEmpty(resourceVersion)) {
            return resourceVersion;
        }

        // 检查应用类型是否是 AGENT
        if (Strings.CS.equals(exportResourceUnit.getAppType(), ResourceTypeEnum.AGENT.toString())) {
            // 获取代理信息
            Agent agent = agentMapper.selectById(exportResourceUnit.getAppId());
            AgentInfo agentInfo = JSONObject.parseObject(obsService.downloadObsFile(agent.getDslPath()),
                AgentInfo.class);

            // 查找工具引用
            ToolReference toolReference = agentInfo.getTools()
                .stream()
                .filter(p -> Strings.CS.equals(p.getToolId(), exportResourceUnit.getResourceId()))
                .findFirst()
                .orElse(null);

            // 如果找到工具引用，返回其最后版本ID
            if (toolReference != null) {
                return toolReference.getLastVersionId();
            }
        }

        // 默认返回资源版本
        return resourceVersion;
    }
}
