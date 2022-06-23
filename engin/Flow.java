package com.jd.umh.flow.engin;

import lombok.Data;
import java.util.List;

/**
 * 流程模板
 * @author luoxiaolin5
 * @date 10:05 2021/6/24
 */
@Data
public class Flow {
    /**
     * 流程名称
     */
    private String name;
    /**
     * 流程编码
     */
    private String code;

//    /**
//     * 账户ID
//     */
//    private String userId;
//    /**
//     * 主任务号
//     */
//    private String taskNo;
    /**
     * 流程上下文
     */
    private FlowContext context;
    /**
     * 流程节点
     */
    private List<Node> nodes;

}
