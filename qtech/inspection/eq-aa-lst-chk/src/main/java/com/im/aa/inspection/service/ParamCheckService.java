package com.im.aa.inspection.service;

import com.im.aa.inspection.comparator.EqLstInspectionModelV3;
import com.im.aa.inspection.comparator.EqLstInspectionModelV4;
import com.im.aa.inspection.entity.param.EqLstParsed;
import com.im.aa.inspection.entity.reverse.EqpReverseDO;
import com.im.aa.inspection.entity.standard.EqLstTplInfoDO;
import com.im.qtech.data.dto.param.EqLstPOJO;
import com.im.qtech.data.dto.reverse.LabelEum;
import org.im.cache.core.Cache;
import org.im.common.dt.Chronos;
import org.im.semiconductor.common.parameter.core.DefaultParameterInspection;
import org.im.semiconductor.common.parameter.core.ParameterInspection;
import org.im.semiconductor.common.parameter.mgr.ParameterRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.StringJoiner;

import static com.im.qtech.data.constant.EqLstInspectionConstants.PROPERTIES_TO_COMPARE;
import static com.im.qtech.data.constant.EqLstInspectionConstants.PROPERTIES_TO_COMPUTE;

/**
 * 参数检查服务
 * 负责设备参数检查的核心业务逻辑
 *
 * @author gaozhilin
 * @email gaoolin@gmail.com
 * @date 2025/09/25
 */
public class ParamCheckService {
    private static final Logger logger = LoggerFactory.getLogger(ParamCheckService.class);
    // 使用专业的参数比较器
    private static final EqLstInspectionModelV4 COMPARATOR = EqLstInspectionModelV4.getInstance();
    private static final String SOURCE_AA_LIST = "aa-list";
    private final CacheService cacheService;
    private final DatabaseService databaseService;

    public ParamCheckService(DatabaseService databaseService, CacheService cacheService) {
        this.databaseService = databaseService;
        this.cacheService = cacheService;
    }

    /**
     * 计算状态码
     */
    private static int calculateStatusCode(ParameterInspection result) {
        boolean hasMissingParams = !result.getEmptyInActual().isEmpty();
        boolean hasExtraParams = !result.getEmptyInStandard().isEmpty();
        boolean hasIncorrectValues = !result.getDifferences().isEmpty();

        // 确定状态码
        if (!hasMissingParams && !hasExtraParams && !hasIncorrectValues) {
            return 0;  // 正常
        } else if (hasMissingParams && !hasExtraParams && !hasIncorrectValues) {
            return 2;  // 少参数
        } else if (!hasMissingParams && hasExtraParams && !hasIncorrectValues) {
            return 4;  // 多参数
        } else if (!hasMissingParams && !hasExtraParams && hasIncorrectValues) {
            return 3;  // 参数值异常
        } else {
            return 5;  // 复合异常
        }
    }

    /**
     * 执行参数检查
     *
     * @param actualObj 实际参数对象
     * @return 检查结果
     */
    public EqpReverseDO performParameterCheck(EqLstParsed actualObj) {
        if (actualObj == null) {
            throw new IllegalArgumentException("实际参数对象不能为空");
        }

        // 初始化检查结果
        EqpReverseDO eqpReverseDO = new EqpReverseDO();
        eqpReverseDO.setSource(SOURCE_AA_LIST);
        eqpReverseDO.setSimId(actualObj.getSimId());
        eqpReverseDO.setModuleId(actualObj.getModuleId());
        eqpReverseDO.setChkDt(Chronos.now());

        // TODO: 获取实际参数对象 增加点检结果中的标签信息。
        Cache<String, String> reverseIgnoredCache = cacheService.getRedisCacheConfig().defaultCache();
        String ignored = reverseIgnoredCache.get(actualObj.getSimId());
        if (ignored != null) {
            eqpReverseDO.setLabel(LabelEum.IGNORE);
        } else {
            eqpReverseDO.setLabel(LabelEum.NORMAL);
        }

        // 获取模板信息（从Redis缓存，通过CacheService）
        EqLstTplInfoDO modelInfoObj = getTplInfoFromCache(actualObj.getModuleId());
        logger.info(">>>>> 获取模板概要 -> {}", modelInfoObj);

        // 模板信息检查
        if (modelInfoObj == null) {
            return createInspectionResult(eqpReverseDO, 1, "Missing Template Information.");
        }

        if (modelInfoObj.getStatus() == 0) {
            return createInspectionResult(eqpReverseDO, 6, "Template Offline.");
        }

        EqLstPOJO modelObj = modelInfoObj.getTpl();
        logger.info(">>>>> 获取模板详情 -> {}", modelObj);

        if (modelObj == null) {
            return createInspectionResult(eqpReverseDO, 7, "Missing Template Detail.");
        }

        // 使用专业比较器进行参数对比
        DefaultParameterInspection inspectionResult = COMPARATOR.compare(modelObj, actualObj, PROPERTIES_TO_COMPARE, PROPERTIES_TO_COMPUTE);

        // 设置检查结果状态
        int statusCode = calculateStatusCode(inspectionResult);
        return createInspectionResult(eqpReverseDO, statusCode, buildDescription(inspectionResult));
    }

    /**
     * 从缓存获取模板信息
     */
    private EqLstTplInfoDO getTplInfoFromCache(String module) {
        if (module == null || module.isEmpty()) {
            logger.error(">>>>> 机型名称不能为空");
            return null;
        }

        Cache<String, EqLstTplInfoDO> eqLstTplInfoDOCache = cacheService.getRedisCacheConfig().getEqLstTplInfoDOCache();
        EqLstTplInfoDO eqLstTplInfoDO = eqLstTplInfoDOCache.get(module);
        if (eqLstTplInfoDO == null) {
            logger.warn(">>>>> 缓存中无此机型模版信息 -> {}", module);
            try {
                EqLstTplInfoDO tplInfo = databaseService.getTplInfo(module);
                if (tplInfo != null) {
                    try {
                        cacheService.getRedisCacheConfig().getEqLstTplInfoDOCache().put(module, tplInfo);
                    } catch (Exception e) {
                        logger.error(">>>>> 缓存中无此机型模版信息 -> {}", module, e);
                    }
                } else {
                    logger.warn(">>>>> 数据库中无此机型模版信息 -> {}", module);
                }
                return tplInfo;
            } catch (Exception e) {
                logger.error(">>>>> 获取模板信息时出错 -> {}", module, e);
            }
        }
        return eqLstTplInfoDO;
    }

    /**
     * 构建描述信息
     */
    private String buildDescription(ParameterInspection result) {
        StringJoiner joiner = new StringJoiner(";");

        result.getEmptyInStandard().keySet().stream().sorted().forEach(prop -> joiner.add(prop + "+"));

        result.getEmptyInActual().keySet().stream().sorted().forEach(prop -> joiner.add(prop + "-"));

        result.getDifferences().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String prop = entry.getKey();
            Map.Entry<Object, Object> map = entry.getValue();
            joiner.add(prop + ":" + map.getValue() + "!=" + map.getKey());
        });

        return joiner.length() > 0 ? joiner.toString() : "Ok.";
    }

    /**
     * 创建检查结果
     */
    private EqpReverseDO createInspectionResult(EqpReverseDO eqpReverseDO, int code, String description) {
        eqpReverseDO.setCode(code);
        eqpReverseDO.setPassed(code == 0);
        eqpReverseDO.setDescription(description);
        return eqpReverseDO;
    }

    /**
     * 执行实际检查逻辑（保留原有简单检查逻辑作为备选）
     *
     * @param param    设备参数
     * @param standard 标准范围
     * @return 检查结果
     */
    private EqpReverseDO performCheck(EqLstParsed param, ParameterRange standard) {
        EqpReverseDO eqpReverseDO = new EqpReverseDO();
        eqpReverseDO.setSimId(param.getSimId());
        eqpReverseDO.setModuleId(param.getModuleId());
        eqpReverseDO.setChkDt(param.getReceivedTime());

        try {
            // 获取参数值
            Object paramValue = param.getAa1();
            if (paramValue == null) {
                eqpReverseDO.setPassed(false);
                eqpReverseDO.setDescription("参数值为空");
                return eqpReverseDO;
            }

            // 如果参数值是数字类型，则进行数值检查
            if (paramValue instanceof Number) {
                double value = ((Number) paramValue).doubleValue();

                // 实际应该使用从数据库获取的标准范围
                if (standard != null) {
                    // 使用ParameterRange进行检查
                    if (standard.contains(value)) {
                        eqpReverseDO.setPassed(true);
                        eqpReverseDO.setDescription("参数在允许范围内");
                    } else {
                        eqpReverseDO.setPassed(false);
                        eqpReverseDO.setDescription("参数超出允许范围");
                    }
                } else {
                    // 简单示例检查
                    if (value >= 0 && value <= 1000) {
                        eqpReverseDO.setPassed(true);
                        eqpReverseDO.setDescription("参数在允许范围内");
                    } else {
                        eqpReverseDO.setPassed(false);
                        eqpReverseDO.setDescription("参数超出允许范围");
                    }
                }
            }
            // 如果参数值是字符串类型
            else if (paramValue instanceof String) {
                String value = (String) paramValue;

                // 检查是否为数值字符串
                try {
                    double numericValue = Double.parseDouble(value);

                    // 实际应该使用从数据库获取的标准范围
                    if (standard != null) {
                        // 使用ParameterRange进行检查
                        if (standard.contains(numericValue)) {
                            eqpReverseDO.setPassed(true);
                            eqpReverseDO.setDescription("参数在允许范围内");
                        } else {
                            eqpReverseDO.setPassed(false);
                            eqpReverseDO.setDescription("参数超出允许范围");
                        }
                    } else {
                        // 简单示例检查
                        if (numericValue >= 0 && numericValue <= 1000) {
                            eqpReverseDO.setPassed(true);
                            eqpReverseDO.setDescription("参数在允许范围内");
                        } else {
                            eqpReverseDO.setPassed(false);
                            eqpReverseDO.setDescription("参数超出允许范围");
                        }
                    }
                } catch (NumberFormatException e) {
                    // 非数值字符串，可以进行其他类型的检查
                    eqpReverseDO.setPassed(true);
                    eqpReverseDO.setDescription("非数值参数，检查通过");
                }
            }
            // 其他类型参数
            else {
                // 对于其他类型，可以基于业务需求进行检查
                eqpReverseDO.setPassed(true);
                eqpReverseDO.setDescription("参数类型无需数值检查");
            }
        } catch (Exception e) {
            eqpReverseDO.setPassed(false);
            eqpReverseDO.setDescription("参数检查过程中发生错误: " + e.getMessage());
            logger.error(">>>>> 参数检查过程中发生错误", e);
        }

        return eqpReverseDO;
    }
}
