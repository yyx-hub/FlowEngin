package com.jd.umh.util;

import com.jd.umh.flow.engin.FlowContext;
import com.jd.umh.flow.engin.Node;
import com.jd.umh.flow.engin.Flow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * xml流程解析器
 *
 * @author luoxiaolin5
 * @date 10:25 2021/6/24
 */
@Slf4j
@Component
public class FlowParser {


/*    public static void main(String[] args) {
        try {
            Flow flow = parser(FlowEnum.ONE_INBOUND_FLOW.getCode());
            System.out.println(flow.getName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }*/
    private static String warehouseType;
    @Value("${config.flow.warehouse}")
    public void setWarehouseType(String warehouseType) {
        FlowParser.warehouseType = warehouseType;
    }

    /**
     * 解析文件
     */
    public static Flow parser(String name){
        Flow flow = new Flow();
        SAXReader reader = new SAXReader();
        InputStream inputStream = FlowParser.class.getClassLoader().getResourceAsStream("flow/" + warehouseType + "/" + name+".xml");
        Document document = null;
        try {
            document = reader.read(inputStream);
        } catch (DocumentException e) {
           log.error("解析模板{}失败：{}",name,e.getMessage());
           return null;
        }
        //获取flow根节点
        Element rootElement = document.getRootElement();
        String flowName = rootElement.attributeValue("name");
        String flowCode = rootElement.attributeValue("code");
        //填充流程模板的名称和编码
        flow.setCode(flowCode);
        flow.setName(flowName);
        //加载节点信息
        List<Node> nodeList = new ArrayList<>();
        List<Element> nodes = rootElement.elements("node");
        for (Element e : nodes) {
            nodeList.add(parseNode(e));
        }
        //转换排序
        nodeList.sort(Comparator.comparingInt(Node::getIndex));
        flow.setNodes(nodeList);
        //流程编排
        layout(flow);
        return flow;
    }

    /**
     * 流程编排
     * @param flow
     */
    private static void layout(Flow flow){
        List<Node> nodes = flow.getNodes();
        //节点关联下一节点
        for(int i=0;i<nodes.size()-1; i++){
            nodes.get(i).setNext(nodes.get(i+1));
        }
        //给流程添加上下文信息
        FlowContext context = new FlowContext();
        context.setFlowCode(flow.getCode());
        context.setFlowName(flow.getName());
        flow.setContext(context);
        //其他节点都引用当前流程的上下文即可，信息共享
        for(Node node:nodes){
            addContext(node,context);
        }
    }

    /**
     * 给节点添加上下文
     * @param node
     * @param context
     */
    private static void addContext(Node node,FlowContext context){
        node.setContext(context);
        List<Node> childs = new ArrayList<>();
        if(node.getSucList()!=null && !node.getSucList().isEmpty()){
            childs.addAll(node.getSucList());
        }
        if(node.getFailList()!=null && !node.getFailList().isEmpty()){
            childs.addAll(node.getFailList());
        }
        if(node.getErrList()!=null && !node.getErrList().isEmpty()){
            childs.addAll(node.getErrList());
        }
        if(!childs.isEmpty()){
            for(Node cn:childs){
                addContext(cn,context);
            }
        }
    }

    /**
     * 解析Node节点元素
     */
    private static Node parseNode(Element e) {
        Node node = new Node();
        node.setCode(e.attributeValue("code"));
        node.setName(e.attributeValue("name"));
        node.setHandler(elementData(e, "handler"));
        node.setMethod(elementData(e, "method"));
        node.setParameterType(elementData(e, "parameterType"));
        node.setParameterValue(elementData(e, "parameterValue"));
        //节点顺序
        String index = elementData(e, "index");
        if(StringUtils.isNotBlank(index)){
            node.setIndex(Integer.parseInt(index));
        }
        //异常节点是否可手动重置
        String retry = elementData(e, "retry");
        if(StringUtils.isNotBlank(retry)){
            node.setRetry(Boolean.valueOf(retry));
        }
        //异常节点是否可手动重置
        String autoRetryNum = elementData(e, "auto-retry-num");
        if(StringUtils.isNotBlank(autoRetryNum)){
            node.setAutoRetryNum(Integer.valueOf(autoRetryNum));
        }


        //成功后子节点
        Element listSuc = e.element("list-suc");
        if (listSuc != null) {
            List<Node> sucList = getNodeList(listSuc,node.getUuid());
            node.setSucList(sucList);
        }
        //失败后子节点
        Element listFail = e.element("list-fail");
        if (listFail != null) {
            List<Node> failList = getNodeList(listFail,node.getUuid());
            node.setFailList(failList);
        }
        //异常后子节点
        Element listErr = e.element("list-err");
        if (listErr != null) {
            List<Node> errList = getNodeList(listErr,node.getUuid());
            node.setErrList(errList);
        }
        return node;
    }

    /**
     * 获取子节点List
     * @param e
     * @return
     */
    private static List<Node> getNodeList(Element e,String pid) {
        List<Element> nodes = e.elements("node");
        List<Node> list = new ArrayList<>();
        for (Element el : nodes) {
            Node node = parseNode(el);
            node.setPid(pid);
            node.setLevel(2);
            list.add(node);
        }
        return list;
    }

    /**
     * 获取元素的text
     */
    private static String elementData(Element e, String name) {
        String text = null;
        Element element = e.element(name);
        if (element != null) {
            text = element.getText();
        }
        return text;
    }


}
