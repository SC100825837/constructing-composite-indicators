package com.jc.research.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CiFrameworkIndicator {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 指标名称
     */
    private String indicatorName;

    /**
     * 指标说明
     */
    private String indicatorDescription;

    /**
     * 指标层级
     */
    private Integer indicatorLevel;

    /**
     * 是否是表头
     */
    private boolean headFlag;

    /**
     * 综合指标架构对象id
     */
    private Long ciFrameworkObjectId;

}