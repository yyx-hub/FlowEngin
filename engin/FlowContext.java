package com.jd.umh.flow.engin;

import lombok.Data;
import org.apache.commons.lang.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 流程上下文
 * @author luoxiaolin5
 * @date 10:05 2021/6/24
 */
@Data
public class FlowContext {

    /**
     * 流程编号
     */
    private String flowCode;
    /**
     * 流程名称
     */
    private String flowName;
    /**
     * 账户ID
     */
    private String userId;
    /**
     * 用户名
     */
    private String userName;
    /**
     * 主任务号
     */
    private String taskNo;
    /**
     * 属性
     */
    private Map<String,Object> attribute;


    private void initAttr(){
        if(attribute == null){
            attribute = new HashMap<>(10);
        }
    }
    /**
     * 获取属性值
     * @param attrName
     * @return
     */
    public Object getAttr(String attrName){
        if(attribute !=null && StringUtils.isNotBlank(attrName)){
            return attribute.get(attrName);
        }
        return null;
    }
    /**
     * 添加属性
     * @param attrName
     * @param attrValue
     * @return
     */
    public FlowContext addAttr(String attrName,Object attrValue){
        initAttr();
        if(StringUtils.isNotBlank(attrName)){
            attribute.put(attrName,attrValue);
        }
        return this;
    }

    /**
     * 删除属性
     * @param attrName
     * @return
     */
    public FlowContext removeAttr(String attrName){
        initAttr();
        if(attribute.containsKey(attrName)){
            attribute.remove(attrName);
        }
        return this;
    }

    /**
     * 清空属性
     * @return
     */
    public FlowContext cleanAttr(){
        initAttr();
        attribute.clear();
        return this;
    }


}
