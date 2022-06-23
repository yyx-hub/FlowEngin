package com.jd.umh.flow.engin;

import com.jd.umh.util.FlowConstant;
import com.jd.umh.util.StrUtil;
import lombok.Data;

import java.util.List;

/**
 * 流程节点
 * @author luoxiaolin5
 * @date 9:47 2021/6/24
 */
@Data
public class Node {

    /**
     * 节点唯一标识，在生成节点实例时生成，标记父子关系及节点失败重试时记录次数
     */
    private String uuid = StrUtil.uuid();
    /**
     * 父节点ID
     */
    private String pid;
    /**
     * 记录流程层级（1为一级节点，如有子节点则为2，以此类推）
     */
    private Integer level = 1;

    /**
     * 流程上下文
     */
    private FlowContext context;
    
    /**
     * 节点编码
     */
    private String code;
    /**
     * 节点名称
     */
    private String name;
    /**
     * 处理器
     */
    private String handler;
    /**
     * 执行方法
     */
    private String method;
    /**
     * 执行方法参数类型
     */
    private String parameterType;
    /**
     * 执行方法参数值（固定参数值，可从配置文件中添加）
     */
    private String parameterValue;
    /**
     * 当前节点参数（包括上个节点的执行结果信息，流程信息，根据节点业务需要动态组装）
     */
    private Object nodeDto;
    /**
     * 节点状态（0初始化1下发2完成并成功3完成但是失败4异常） 默认为初始化
     */
    private Integer status= FlowConstant.NODE_INIT;
    /**
     * 节点顺序
     */
    private Integer index;
    /**
     * 节点异常是否可手动重置(默认为false)
     */
    private Boolean retry = false;
    /**
     * 节点异常自动重试次数（默认自动重试3次）
     */
    private Integer autoRetryNum = 3;
    /**
     * 下一节点
     */
    private Node next;
    /**
     * 成功条件子节点
     */
    private List<Node> sucList;
    /**
     * 失败条件子节点
     */
    private List<Node> failList;
    /**
     * 节点异常时执行子节点
     */
    private List<Node> errList;

}
