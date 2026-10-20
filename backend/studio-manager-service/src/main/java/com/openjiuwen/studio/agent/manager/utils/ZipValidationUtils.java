/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2025-2026. All rights reserved.
 */

package com.openjiuwen.studio.agent.manager.utils;

import com.openjiuwen.studio.agent.common.enums.StudioError;
import com.openjiuwen.studio.agent.common.exception.AgentStudioException;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * ZIP包校验工具类
 * 用于严格校验skill-creator.zip文件的格式、大小、安全性以及SKILL.md文件的内容
 *
 */
@Slf4j
public class ZipValidationUtils {

    private static ZipFile openZipFile(File zipFile) throws IOException {
        try {
            return new ZipFile(zipFile);
        } catch (ZipException e) {
            return new ZipFile(zipFile, Charset.forName("GBK"));
        }
    }

    private static final long MAX_ZIP_SIZE = 10L * 1024 * 1024; // 10MB

    private static final long MAX_EXTRACTED_SIZE = 100L * 1024 * 1024; // 100MB

    private static final int MAX_ENTRY_COUNT = 500;

    private static final int MAX_DEPTH = 10;

    private static final Set<String> ALLOWED_FRONTMATTER_FIELDS = Set.of(
            "name", "description", "license", "allowed-tools", "metadata", "compatibility"
            , "version", "category", "sources", "- https"
    );

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$");

    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^[^<]+$");


    /**
     * 校验ZIP包并返回解析后的 frontmatter。
     *
     * <p>本方法不再依赖静态变量传递 frontmatter，避免并发导入时被其他线程覆盖。
     * 调用方可直接通过返回值获取 SKILL.md 中的 name/description 等字段。</p>
     *
     * @param zipFile ZIP文件
     * @return 解析后的 frontmatter
     * @throws AgentStudioException 校验失败时抛出异常
     */
    public static Map<String, Object> validateZipFile(File zipFile) {
        // 1. 文件格式检查
        validateFileFormat(zipFile);

        // 2. 文件大小检查
        validateFileSize(zipFile);

        // 3. ZIP安全检查
        validateZipSecurity(zipFile);

        // 4. 解压后内容检查（内部解析 frontmatter 并返回）
        return validateExtractedContent(zipFile);
    }

    /**
     * 验证文件格式
     */
    private static void validateFileFormat(File zipFile) {
        if (!zipFile.exists() || !zipFile.isFile()) {
            log.error("The file does not exist or is not a file: {}", zipFile.getAbsolutePath());
            throw new AgentStudioException(StudioError.ILLEGAL_FILE);
        }

        // Magic Number检查 (ZIP文件头特征)
        if (!isZipFile(zipFile)) {
            log.error("Invalid file type.");
            throw new AgentStudioException(StudioError.ILLEGAL_FILE_TYPE);
        }
    }

    /**
     * 验证ZIP文件的Magic Number
     */
    public static boolean isZipFile(File zipFile) {
        try (FileInputStream fis = new FileInputStream(zipFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            byte[] header = new byte[4];
            int bytesRead = bis.read(header);

            // ZIP文件头签名: 0x50 0x4B 0x03 0x04
            return isValidZipHeader(header, bytesRead);
        } catch (IOException e) {
            log.error("Failed to check the magic number of the ZIP file: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 验证 SKILL 解压前文件大小
     */
    private static void validateFileSize(File zipFile) {
        long fileSize = zipFile.length();
        if (fileSize > MAX_ZIP_SIZE) {
            log.error("The file size exceeds the limit: {}", fileSize);
            throw new AgentStudioException(StudioError.SKILL_SIZE_EXCEED_LIMIT);
        }
    }

    /**
     * 验证ZIP安全性
     */
    private static void validateZipSecurity(File zipFile) {
        try (ZipFile zip = openZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            int entryCount = 0;
            long totalSize = 0L;
            Map<String, Integer> pathDepthMap = new HashMap<>();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;

                // 条目数检查
                if (entryCount > MAX_ENTRY_COUNT) {
                    log.error("The number of ZIP entries exceeds the limit. A maximum of {} entries are allowed.",
                            MAX_ENTRY_COUNT);
                    throw new AgentStudioException(StudioError.ZIP_FILE_COUNT_EXCEED_LIMIT);
                }

                // 嵌套ZIP检查
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    log.error("ZIP files cannot be nested in other ZIP files: {}", entry.getName());
                    throw new AgentStudioException(StudioError.FILE_NESTED_ZIP);
                }

                // 路径深度检查
                int depth = calculatePathDepth(entry.getName());
                if (depth > MAX_DEPTH) {
                    log.error("The depth of the ZIP file path is too deep. The maximum number of allowed levels is {}." +
                            " Current path: {}", MAX_DEPTH, entry.getName());
                    throw new AgentStudioException(StudioError.ZIP_PATH_TOO_DEEP);
                }

                // 路径穿越检查
                if (containsPathTraversal(entry.getName())) {
                    log.error("The ZIP file contains a path traversal vulnerability, an insecure path: {}", entry.getName());
                    throw new AgentStudioException(StudioError.INSECURE_PATH);
                }

                // 符号链接检查
                if (isSymbolicLink(entry)) {
                    log.error("The ZIP file contains symbolic links, unsafe paths: {}", entry.getName());
                    throw new AgentStudioException(StudioError.UNSAFE_PATH);
                }

                totalSize += entry.getSize();
                pathDepthMap.put(entry.getName(), depth);

            }

            // 解压后总大小检查
            if (totalSize > MAX_EXTRACTED_SIZE) {
                log.error("The extracted size exceeds the limit, the maximum size is 100 MB: {}", formatFileSize(totalSize));
                throw new AgentStudioException(StudioError.SKILL_SIZE_EXCEED_LIMIT);
            }

        } catch (IOException e) {
            log.error("ZIP verification failed: {}", e.getMessage(), e);
            throw new AgentStudioException(StudioError.ILLEGAL_FILE);
        }
    }

    /**
     * 计算路径深度
     */
    private static int calculatePathDepth(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }

        String normalizedPath = path.replace('\\', '/');
        String[] parts = normalizedPath.split("/");

        // 移除空字符串（连续分隔符或路径末尾的分隔符）
        int nonEmptyParts = 0;
        for (String part : parts) {
            if (!part.isEmpty()) {
                nonEmptyParts++;
            }
        }

        return nonEmptyParts;
    }

    /**
     * 检查路径穿越
     */
    private static boolean containsPathTraversal(String path) {
        return path.contains("../") || path.contains("..\\") || path.startsWith("..");
    }

    /**
     * 检查是否为符号链接
     */
    private static boolean isSymbolicLink(ZipEntry entry) {
        return isWindowsSymlink(entry) || isUnixSymlink(entry);
    }

    /**
     * 检查Windows符号链接特征
     */
    private static boolean isWindowsSymlink(ZipEntry entry) {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return fileName.endsWith(".lnk") || fileName.endsWith(".url") ||
                fileName.equals("desktop.ini");
    }

    /**
     * 检查Unix符号链接特征
     */
    private static boolean isUnixSymlink(ZipEntry entry) {
        String name = entry.getName();
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return fileName.startsWith("._") || fileName.endsWith(".symlink") ||
                fileName.endsWith(".link");
    }

    /**
     * 验证解压后的内容。
     *
     * <p>解压到临时目录的根级（不再拼接 zipFileName 子层），使平铺包的 SKILL.md 真正位于根级，
     * 单文件夹包的 {name}/SKILL.md 也位于根级下。tempDir 即为 extractionRoot，向下游透传，
     * 供 isValidDirectoryName() 判断平铺结构使用。</p>
     *
     * @return 从 SKILL.md 解析得到的 frontmatter
     */
    private static Map<String, Object> validateExtractedContent(File zipFile) {
        Path tempDir = null;
        try {
            // 直接在 tempDir 根解压，不再创建 zipFileName 子目录
            tempDir = Files.createTempDirectory("skill-validate");
            unzip(zipFile, tempDir);

            // 验证目录结构
            validateDirectoryStructure(tempDir);

            // 验证SKILL.md文件并返回 frontmatter
            return validateSkillMdFile(tempDir);

        } catch (IOException e) {
            log.error("Failed to verify the decompressed content: {}", e.getMessage(), e);
            throw new AgentStudioException(StudioError.RESOURCE_READER_ERROR);
        } finally {
            if (tempDir != null) {
                deleteDirectoryQuietly(tempDir);
            }
        }
    }

    /**
     * 解压ZIP文件
     */
    private static void unzip(File zipFile, Path destDir) throws IOException {
        try (ZipFile zip = openZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                if (entry.isDirectory()) {
                    continue;
                }

                Path destPath = destDir.resolve(entry.getName());

                // 确保父目录存在
                Files.createDirectories(destPath.getParent());

                // 解压文件
                try (InputStream is = zip.getInputStream(entry);
                     OutputStream os = Files.newOutputStream(destPath)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        os.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    /**
     * 验证目录结构。
     *
     * <p>平铺结构优先判定：根级有 SKILL.md 即视为平铺包，允许任意数量的附属文件/文件夹，
     * 但显式禁止子目录下再出现 SKILL.md，避免后续 Files.walk 查找歧义。
     * 根级无 SKILL.md 时才进入"单子目录含 SKILL.md"分支。</p>
     */
    private static void validateDirectoryStructure(Path baseDir) throws IOException {
        // 情况1：根目录有 SKILL.md（平铺结构），允许任意数量的附属文件夹/文件
        Path rootSkillMd = baseDir.resolve("SKILL.md");
        if (Files.exists(rootSkillMd)) {
            // 但不允许子目录下再出现 SKILL.md，避免后续 findFirst 查找歧义
            try (Stream<Path> paths = Files.walk(baseDir)) {
                long childSkillMdCount = paths
                        .filter(p -> !p.equals(rootSkillMd))
                        .filter(p -> p.getFileName() != null
                                && "SKILL.md".equalsIgnoreCase(p.getFileName().toString()))
                        .count();
                if (childSkillMdCount > 0) {
                    log.error("Multiple SKILL.md detected: root + {} child", childSkillMdCount);
                    throw new AgentStudioException(StudioError.FILES_NUMBER_DOES_NOT_MATCH);
                }
            }
            return; // 有效结构（平铺）
        }

        // 情况2：根目录无 SKILL.md，需恰有 1 个子目录含 SKILL.md（单文件夹结构）
        try (Stream<Path> paths = Files.walk(baseDir)) {
            List<Path> allDirs = paths.filter(Files::isDirectory).filter(p -> !p.equals(baseDir)).toList();

            // 过滤掉空目录
            List<Path> nonEmptyDirs = new ArrayList<>();
            for (Path dir : allDirs) {
                try (Stream<Path> dirContents = Files.list(dir)) {
                    if (dirContents.findAny().isPresent()) {
                        nonEmptyDirs.add(dir);
                    }
                }
            }

            List<Path> skillDirs = new ArrayList<>();
            for (Path dir : nonEmptyDirs) {
                if (Files.exists(dir.resolve("SKILL.md"))) {
                    skillDirs.add(dir);
                }
            }

            if (skillDirs.size() == 1) {
                return; // 有效结构（单文件夹）
            }

            // 其他情况都是无效的
            log.error("Directory check failed - skillDirs: {}, nonEmpty: {}, hasRoot: {}",
                    skillDirs.size(), nonEmptyDirs.size(), false);
            throw new AgentStudioException(StudioError.FILES_NUMBER_DOES_NOT_MATCH);
        }
    }

    /**
     * 验证SKILL.md文件，并返回解析得到的 frontmatter。
     *
     * <p>优先取 baseDir/SKILL.md（与 validateDirectoryStructure 的平铺优先策略对齐），
     * 避免 Files.walk().findFirst 找到任意 SKILL.md 与结构判定依据不一致。
     * 同时将 baseDir 作为 extractionRoot 透传给 validateFrontmatter / isValidDirectoryName。</p>
     *
     * @param baseDir 解压根目录（即 tempDir）
     * @return 解析得到的 frontmatter
     */
    private static Map<String, Object> validateSkillMdFile(Path baseDir) throws IOException {
        // 优先取根级 SKILL.md
        Path skillMdFile = baseDir.resolve("SKILL.md");
        if (!Files.exists(skillMdFile)) {
            // 兜底：查找任意位置的 SKILL.md（单文件夹结构下命中 {name}/SKILL.md）
            try (Stream<Path> paths = Files.walk(baseDir)) {
                skillMdFile = paths.filter(p ->
                        p.getFileName() != null &&
                                "SKILL.md".equalsIgnoreCase(p.getFileName().toString())
                ).findFirst().orElse(null);
            }
        }

        if (skillMdFile == null) {
            log.error("The ZIP file does not contain a SKILL.md file.");
            throw new AgentStudioException(StudioError.SKILL_MD_IS_MISSING);
        }

        // 读取文件内容
        String content = Files.readString(skillMdFile);

        // 解析YAML
        Map<String, Object> frontmatterYaml = parseYamlFrontmatter(content);

        // 验证frontmatterYaml字段，并将 baseDir 作为 extractionRoot 透传
        validateFrontmatter(frontmatterYaml, skillMdFile, baseDir);

        return frontmatterYaml;
    }

    /**
     * 解析YAML frontmatter。
     *
     * <p>本方法仅返回解析结果，不再写入静态变量，避免并发导入时的线程安全问题。
     * 历史问题：原实现同时写入 ZipValidationUtils.frontmatter 静态变量，
     * 在多线程并发导入时会互相覆盖，导致 frontmatter 数据不一致。</p>
     */
    private static Map<String, Object> parseYamlFrontmatter(String content) {
        Map<String, Object> result = new LinkedHashMap<>();

        // YAML frontmatter解析
        if (content.startsWith("---")) {
            int endFront = content.indexOf("---", 3);
            if (endFront != -1) {
                String frontmatterText = content.substring(3, endFront).trim();

                // 解析key: value格式
                String[] lines = frontmatterText.split("\n");
                for (String rawLine : lines) {
                    if (!rawLine.isEmpty() && (rawLine.charAt(0) == ' ' || rawLine.charAt(0) == '\t')) {
                        continue;
                    }

                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String key = line.substring(0, colonIndex).trim();
                        String value = line.substring(colonIndex + 1).trim();

                        // 处理布尔值
                        if ("true".equalsIgnoreCase(value)) {
                            value = "true";
                        } else if ("false".equalsIgnoreCase(value)) {
                            value = "false";
                        }

                        result.put(key, value);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 验证frontmatter字段，并校验 SKILL.md 所在目录名是否与 name 字段一致。
     *
     * @param frontmatterYaml 从 SKILL.md 解析得到的 frontmatter
     * @param skillMdFile SKILL.md 文件路径
     * @param extractionRoot 解压根目录（即 tempDir），用于判断平铺结构
     */
    private static void validateFrontmatter(Map<String, Object> frontmatterYaml,
                                            Path skillMdFile,
                                            Path extractionRoot) {

        // 检查白名单字段
        for (String field : frontmatterYaml.keySet()) {
            if (!ALLOWED_FRONTMATTER_FIELDS.contains(field)) {
                log.error("Invalid frontmatter field: {}", field);
                throw new AgentStudioException(StudioError.SKILL_MD_INVALID_FIELD);
            }
        }

        // 检查必填字段
        Object name = frontmatterYaml.get("name");
        if (name == null || name.toString().trim().isEmpty()) {
            log.error("The name cannot be empty.");
            throw new AgentStudioException(StudioError.NAME_FIELD_MISSING);
        } else {
            validateName(name.toString());
        }

        Object description = frontmatterYaml.get("description");
        if (description == null || description.toString().trim().isEmpty()) {
            log.error("The description cannot be empty.");
            throw new AgentStudioException(StudioError.DESCRIPTION_FIELD_MISSING);
        } else {
            validateDescription(description.toString());
        }

        // 检查compatibility字段
        Object compatibility = frontmatterYaml.get("compatibility");
        if (compatibility != null) {
            validateCompatibility(compatibility.toString());
        }

        // 验证目录名与name字段一致，透传 extractionRoot 用于平铺结构判定
        if (!isValidDirectoryName(skillMdFile, extractionRoot, name.toString())) {
            log.error("The directory name and the name field in the SKILL.md file do not match.");
            throw new AgentStudioException(StudioError.NAME_FORMAT_MISMATCH);
        }
    }

    /**
     * 验证name字段
     */
    private static void validateName(String name) {
        if (name.isEmpty() || name.length() > 64) {
            log.error("The length must be between 1 and 64 characters.");
            throw new AgentStudioException(StudioError.NAME_FORMAT_MISMATCH);
        }

        if (!NAME_PATTERN.matcher(name).matches()) {
            log.error("The name field does not meet the specifications.");
            throw new AgentStudioException(StudioError.NAME_FORMAT_MISMATCH);
        }
    }

    /**
     * 验证description字段
     */
    private static void validateDescription(String description) {
        if (description.isEmpty() || description.length() > 1024) {
            log.error("The length must be between 1 and 1024 characters.");
            throw new AgentStudioException(StudioError.DESCRIPTION_LENGTH_EXCEED);
        }

        if (!DESCRIPTION_PATTERN.matcher(description).matches()) {
            log.error("Cannot contain XML tags");
            throw new AgentStudioException(StudioError.INVALID_CHARACTER);
        }
    }

    /**
     * 验证compatibility字段
     */
    private static void validateCompatibility(String compatibility) {
        if (compatibility.length() > 500) {
            log.error("The length must be between 1 and 500 characters.");
            throw new AgentStudioException(StudioError.COMPATIBILITY_LENGTH_EXCEED);
        }
    }

    /**
     * 验证目录名是否与 SKILL.md frontmatter 中的 name 字段一致。
     *
     * <p>平铺结构判定：parentDir.equals(extractionRoot) 表示 SKILL.md 位于解压根目录，
     * 此时不比对目录名（因为解压根目录名是系统生成的临时目录名，与 skill name 无关）。
     * 单文件夹结构：取父目录最后一段（如 "my-skill"）与 name 比对。</p>
     *
     * <p>注意：原实现用 !parentDir.isAbsolute() 判断平铺结构是无效的——
     * Files.createTempDirectory() 返回的路径始终是绝对路径，该分支恒为 false，
     * 平铺包实际会走到 equalsIgnoreCase(nameField) 比对并几乎必然失败。</p>
     */
    private static boolean isValidDirectoryName(Path skillMdFile, Path extractionRoot, String nameField) {
        Path parentDir = skillMdFile.getParent();

        // 父目录就是解压根 → SKILL.md 在根级（平铺结构），无需比对目录名
        if (parentDir == null || parentDir.equals(extractionRoot)) {
            return true;
        }

        // 单文件夹结构：取父目录最后一段（如 "my-skill"）与 name 比对
        try {
            Path dirNamePart = parentDir.getFileName();
            if (dirNamePart != null) {
                return dirNamePart.toString().equalsIgnoreCase(nameField);
            }
        } catch (Exception e) {
            log.error("Unexpected error while validating directory name: {}", e.getMessage());
            throw new AgentStudioException(StudioError.ILLEGAL_FILE_NAME);
        }

        return true;
    }

    /**
     * 删除目录
     */
    private static void deleteDirectoryQuietly(Path path) {
        try {
            try (Stream<Path> paths = Files.walk(path)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("The file cannot be deleted: {}", p, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("The directory cannot be deleted: {}", path, e);
        }
    }

    /**
     * 格式化文件大小
     */
    private static String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        }
    }

    private static boolean isValidZipHeader(byte[] header, int bytesRead) {
        if (bytesRead != 4) {
            return false;
        }
        return header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
    }

    /**
     * 如果是平铺结构（根级有 SKILL.md），重打包为 {name}/ 结构。
     * 单文件夹结构则原样返回入参 zipFile。
     *
     * <p><b>安全约定</b>：本方法内部调用 {@link #unzip(File, Path)} 解压入参 zip，
     * 不再做路径穿越/符号链接/条目数等安全校验。调用方<b>必须</b>先调用
     * {@link #validateZipFile(File)} 完成安全校验后再调用本方法，
     * 否则可能解压恶意构造的 zip 导致路径穿越风险。</p>
     *
     * <p><b>边缘场景</b>：若平铺包根级恰好存在与 skillName 同名的子目录
     * （如 SKILL.md + my-skill/helper.py，其中 name=my-skill），
     * Files.createDirectories 不会报错（目录已存在），
     * Files.list 循环对该同名目录的 !entry.equals(targetDir) 为 false → 跳过移动，
     * 该目录原内容保留在 tempDir/my-skill/ 下；后续 zipDirectory(tempDir/my-skill, ...)
     * 会把原内容一并打包。最终结果正确（my-skill/SKILL.md + my-skill/helper.py），
     * 仅代码意图不直观。若需更清晰可改为先把同名目录重命名为临时名（如
     * skillName + ".orig"），再统一 move；本次不强制改造，仅保留注释说明。</p>
     *
     * @param zipFile 已通过 {@link #validateZipFile(File)} 校验的 zip 文件
     * @param skillName SKILL.md frontmatter 中的 name 字段值
     * @return 重打包后的 zip（平铺结构）或原入参（单文件夹结构）
     */
    public static File repackIfFlat(File zipFile, String skillName) {
        Path tempDir = null;
        File repackedZip = null;  // 提前声明，便于 catch/finally 引用
        try {
            tempDir = Files.createTempDirectory("skill-repack");
            // 复用现有 private unzip（同类内可直接调用）
            unzip(zipFile, tempDir);

            Path rootSkillMd = tempDir.resolve("SKILL.md");
            if (!Files.exists(rootSkillMd)) {
                // 单文件夹结构，无需重打包
                return zipFile;
            }

            // 平铺结构：创建 {skillName}/ 子目录，将根级所有条目移入
            Path targetDir = tempDir.resolve(skillName);
            Files.createDirectories(targetDir);
            try (Stream<Path> entries = Files.list(tempDir)) {
                for (Path entry : entries.toList()) {
                    if (!entry.equals(targetDir)) {
                        Path dest = targetDir.resolve(entry.getFileName());
                        Files.move(entry, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            // 重新打包为 zip，使用 UTF-8 编码避免中文文件名乱码
            repackedZip = new File(zipFile.getParent(), skillName + ".zip");
            zipDirectory(targetDir, repackedZip, skillName);
            log.info("Repacked flat skill package to {}/ structure: {}", skillName, repackedZip.getName());
            return repackedZip;
        } catch (IOException e) {
            // 异常路径清理半成品 repackedZip，避免磁盘泄漏
            if (repackedZip != null && repackedZip.exists()) {
                if (!repackedZip.delete()) {
                    repackedZip.deleteOnExit();
                    log.warn("Failed to delete partial repacked zip: {}", repackedZip.getAbsolutePath());
                }
            }
            log.error("Failed to repack flat skill: {}", e.getMessage(), e);
            throw new AgentStudioException(StudioError.RESOURCE_READER_ERROR);
        } finally {
            if (tempDir != null) {
                deleteDirectoryQuietly(tempDir);  // 复用现有 private 方法
            }
        }
    }

    /**
     * 将 dir 内所有内容打包为 zip，条目名为相对 dir 的相对路径，根目录名为 rootEntryName。
     *
     * <p>注意：不保留空目录条目（原平铺包中的空 __pycache__/ 等会在重打包后丢失，
     * 对 skill 运行时无影响，因 unzip 解压时根据文件父目录会自动重建非空目录）。</p>
     */
    private static void zipDirectory(Path dir, File outputZip, String rootEntryName) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputZip);
             ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
            try (Stream<Path> stream = Files.walk(dir)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    String relative = rootEntryName + "/" + dir.relativize(file).toString()
                            .replace('\\', '/');
                    ZipEntry entry = new ZipEntry(relative);
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                }
            }
        }
    }

}
