package com.jd.umh.flow.engin;

import com.alibaba.fastjson.annotation.JSONField;

import java.io.Serializable;

/**
 * 流程节点参数Dto
 * @author luoxiaolin5
 * @date 16:23 2021/6/25
 */
public class NodeDto implements Serializable {
    /**
     * 当前节点
     */
    @JSONField(serialize = false)
    public Node node;
    /**
     * 当前节点执行结果（用于执行子节点逻辑）
     */
    @JSONField(serialize = false)
    public boolean status;
    /**
     * 当前逻辑是否为自动重试状态
     */
    @JSONField(serialize = false)
    public boolean retryStatus;


}
