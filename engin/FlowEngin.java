package com.jd.umh.flow.engin;

import com.alibaba.fastjson.JSONObject;
import com.jd.umh.domain.constant.Constant;
import com.jd.umh.flow.service.TaskFlowRecordService;
import com.jd.umh.util.FlowConstant;
import com.jd.umh.util.FlowParser;
import com.jd.umh.util.StrUtil;
import com.jd.umh.util.constant.FlowEnum;
import com.jd.umh.flow.entity.TaskFlowRecordEntity;
import com.jd.umh.util.RedisUtils;
import com.jd.umh.util.SpringContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流程执行引擎
 *
 * @author luoxiaolin5
 * @date 14:37 2021/6/24
 */
@Component
@Slf4j
public class FlowEngin {

    @Autowired
    private TaskFlowRecordService taskFlowRecordService;

    //TODO 当前线程池既执行流程又执行节点，可监控线程状态在页面展示
    /**
     * 流程执行线程池
     */
    public ThreadPoolExecutor threadPool = new ThreadPoolExecutor(5, 10,
            0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(2000), new FlowThreadFactory());

    /**
     * 使用自定义线程池，定义线程名称，与其他线程区分
     */
    static class FlowThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        FlowThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() :
                    Thread.currentThread().getThreadGroup();
            namePrefix = "flowPool-" +
                    poolNumber.getAndIncrement() +
                    "-flowThread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }


    @Autowired
    private RedisUtils redisUtils;

    /**
     * 查看当前流程主节点和子节点都完成
     *
     * @param nodes
     * @return
     */
    private boolean finish(List<Node> nodes) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {

        }
        for (Node node : nodes) {
            if (FlowConstant.NODE_INIT.equals(node.getStatus())
                    || FlowConstant.NODE_RUN.equals(node.getStatus())) {
                return false;
            }
            List<Node> sucList = node.getSucList();
            List<Node> failList = node.getFailList();
            List<Node> errList = node.getErrList();
            List<Node> child = new ArrayList<>();
            if (FlowConstant.NODE_SUC.equals(node.getStatus())
                    && sucList != null && !sucList.isEmpty()) {
                child.addAll(sucList);
            }
            if (FlowConstant.NODE_FAIL.equals(node.getStatus())
                    && failList != null && !failList.isEmpty()) {
                child.addAll(failList);
            }
            if (FlowConstant.NODE_ERR.equals(node.getStatus())
                    && errList != null && !errList.isEmpty()) {
                child.addAll(errList);
            }
            if (!child.isEmpty()) {
                return finish(child);
            }
        }

        return true;
    }

    /**
     * 启动流程实例
     */
    public void start(String flowName, Object nodeDto, String userId) {
        String flowKey = genKey(flowName, userId);
        if (StringUtils.isNotBlank(redisUtils.get(flowKey))) {
            log.error("当前流程 {} 正在执行中，请勿重复操作！",flowKey);
            throw new RuntimeException("正在执行，请稍后！");
        }
        //加载流程实例
        com.jd.umh.flow.engin.Flow instance = FlowParser.parser(flowName);
        if (instance == null) {
            return;
        }
        log.info("{}流程启动。。。",instance.getName());
        long start = System.currentTimeMillis();
        //存放正在执行的流程实例放入redis
        cacheFlow(flowKey,instance);
        //首个节点添加节点参数
        instance.getNodes().get(0).setNodeDto(nodeDto);
        threadPool.execute(() -> {
            //循环遍历执行中的流程
            while (true) {
                List<Node> nodes = instance.getNodes();
                if (finish(nodes)) {
                    log.info("{}流程结束,耗时{}ms", instance.getName(),System.currentTimeMillis()-start);
                    //没有待执行节点则表示流程已完成，移除列表即可
                    redisUtils.delete(flowKey);
                    return;
                }
                //有待执行的流程节点，按顺序执行
                for (Node node : nodes) {
                    if (FlowConstant.NODE_INIT.equals(node.getStatus())
                            && !execute(node)) {
                        //执行节点,如果节点执行失败则终止当前流程
                        log.error("节点 {} 执行失败，{}流程结束,,耗时{}ms",node.getName(),instance.getName(),System.currentTimeMillis()-start);
                        //节点异常直接移除流程列表
                        redisUtils.delete(flowKey);
                        return;
                    }
                }
            }
        });

    }

    /**
     * 缓存正在执行的流程
     * @param flowKey
     * @param instance
     */
    private void cacheFlow(String flowKey, com.jd.umh.flow.engin.Flow instance) {
        if(flowKey.contains(FlowEnum.WEIGHT_REPORT_FLOW.getCode())){
            redisUtils.set(flowKey, instance, 2);
        }else {
            redisUtils.set(flowKey, instance, 30);
        }
    }

    /**
     * 执行node节点
     * @param node
     */
    private  boolean execute(Node node) {
        log.info("节点 {} 准备执行。。。。",node.getName());
        //节点执行时先设置为执行中
        node.setStatus(FlowConstant.NODE_RUN);
        //获取节点处理器
        String handlerStr = StrUtil.firstLower(node.getHandler());
        String methodStr = node.getMethod();
        String parameterType = node.getParameterType();
        if (!SpringContextUtils.containsBean(handlerStr)) {
            log.error("节点 {} 执行失败，错误信息：{}", node.getName(), handlerStr + " 不存在");
            return false;
        }
        Object handler = SpringContextUtils.getBean(handlerStr);
        try {
            Method method = handler.getClass().getMethod(methodStr, Class.forName(parameterType));
            Object nodeDto = node.getNodeDto();
            //将当前节信息添加到节点参数中
            fillField(nodeDto, "node", node);
            //记录节点执行记录
            saveRecord(node);
            log.info("节点 {} 开始执行。。。。",node.getName());
            Object result = method.invoke(handler, nodeDto);
            //当前节点处理结果为下个节点处理的参数
            Node next = node.getNext();
            if (next != null) {
                next.setNodeDto(result);
            }
            //节点执行结果状态
            boolean status = getStatus(result);
            log.info("节点 {} 执行完毕，执行结果{}",node.getName(),status);
            //更新节点执行记录
            updateRecord(node, status);
            //根据当前节点执行状态  处理子节点
            List<Node> childNodes = status ? node.getSucList() : node.getFailList();
            if (childNodes != null && !childNodes.isEmpty()) {
                //如果有子节点，则执行子节点，子节点参数可用父节点执行的Handler中获取
                for (Node child : childNodes) {
                    //子节点因为共享了父节点的执行结果作为参数变量，会导致线程安全问题，所以序列化出多个参数实例可避免该问题
                    Class<?> clazz = Class.forName(child.getParameterType());
                    Object childDto = clazz.newInstance();
                    BeanUtils.copyProperties(result,childDto);
                    fillField(childDto, "status", status);
                    child.setNodeDto(childDto);
                    threadPool.execute(() -> execute(child));
                }
            }
            return status;
        } catch (Exception e) {
            log.error("节点 {} 执行失败，错误信息：{}", node.getName(),  e.getMessage());
            return errRecord(node, getErrMsg(e));
        }
    }

    /**
     * 转换异常描述
     * @param e
     * @return
     */
    private String getErrMsg(Exception e){
        String msg = "未知异常："+e.getMessage();
        if(e instanceof InvocationTargetException){
            InvocationTargetException targetException = (InvocationTargetException) e;
            Throwable target = targetException.getTargetException();
            if( target.getCause() instanceof SocketTimeoutException){
                msg = "网络异常，链接超时";
            }else if( target.getCause() instanceof ConnectException){
                msg = "网络异常，链接超时";
            }else if( target.getCause() instanceof UnknownHostException){
                msg = "网络异常，链接超时";
            }
        }
        return msg;
    }

    /**
     * 节点重试
     *
     * @param node
     * @return
     */
    public boolean retryNode(Node node) {
        //获取节点处理器
        String handlerStr = StrUtil.firstLower(node.getHandler());
        String methodStr = node.getMethod();
        String parameterType = node.getParameterType();
        if (!SpringContextUtils.containsBean(handlerStr)) {
            log.error("节点 {} 重试失败，错误信息：{}", node.getName(), handlerStr + " 不存在");
            return false;
        }
        Object handler = SpringContextUtils.getBean(handlerStr);
        try {
            Class<?> paramClass = Class.forName(parameterType);
            Method method = handler.getClass().getMethod(methodStr, paramClass);
            String dtoStr = JSONObject.toJSONString(node.getNodeDto());
            Object nodeDto = JSONObject.parseObject(dtoStr, paramClass);
            //将当前节信息添加到节点参数中
            fillField(nodeDto, "node", node);
            //将节点参数信息改为重试状态（如果为重试状态可以在对应Handler内控制数据发送逻辑，屏蔽其他控制）
            fillField(nodeDto, "retryStatus", true);
            Object result = method.invoke(handler, nodeDto);
            //节点执行结果状态
            boolean status = getStatus(result);
            log.info("异常节点 {} 重试成功，返回结果状态为{}",node.getName(),status);
            return true;
        } catch (Exception e) {
            log.error("节点 {} 重试失败,参数{}，错误信息：{}", node.getName(), node.getNodeDto(), e.getMessage());
            return false;
        }
    }


    /**
     * 保存节点记录
     *
     * @param node
     */
    private void saveRecord(Node node) {
        TaskFlowRecordEntity record = taskFlowRecordService.getByNodeId(node.getUuid());
        if (record != null) {
          return;
        }
        //生成节点记录
        record = new TaskFlowRecordEntity();
        record.setNodeId(node.getUuid());
        record.setPnodeId(node.getPid());
        record.setTaskNo(node.getContext().getTaskNo());
        record.setFlowCode(node.getContext().getFlowCode());
        record.setNodeCode(node.getCode());
        record.setNodeDesc(node.getName());
        record.setNodeStatus(node.getStatus());
        //获取当前等级
        if(StringUtils.isNotBlank(record.getPnodeId())){
            TaskFlowRecordEntity pNode = taskFlowRecordService.getByNodeId(record.getPnodeId());
            if(pNode != null ){
                record.setNodeLevel(pNode.getNodeLevel()+1);
            }
        }

        //是否有子节点
        record.setHasChild(Constant.NO);
        boolean sucList = node.getSucList() != null && !node.getSucList().isEmpty();
        boolean failList = node.getFailList() != null && !node.getFailList().isEmpty();
        boolean errList = node.getErrList() != null && !node.getErrList().isEmpty();
        if (sucList || failList || errList) {
            record.setHasChild(Constant.YES);
        }
        //保存节点记录
        taskFlowRecordService.saveOrUpdateRecord(record);
    }

    /**
     * 更新节点记录
     *
     * @param node
     */
    private synchronized void updateRecord(Node node, boolean result) {
        node.setStatus(result ? FlowConstant.NODE_SUC : FlowConstant.NODE_FAIL);
        TaskFlowRecordEntity record = taskFlowRecordService.getByNodeId(node.getUuid());
        if (record != null) {
            record.setNodeStatus(node.getStatus());
            record.setTaskNo(node.getContext().getTaskNo());
            taskFlowRecordService.saveOrUpdateRecord(record);
        }
    }

    /**
     * 更新节点异常执行记录
     *
     * @param node
     * @param errMsg
     * @return
     */
    private boolean errRecord(Node node, String errMsg) {
        log.info("节点 {} 执行异常：{}", node.getName(), errMsg);
        node.setStatus(FlowConstant.NODE_ERR);
        TaskFlowRecordEntity record = taskFlowRecordService.getByNodeId(node.getUuid());
        if (record == null) {
            return false;
        }
        record.setNodeStatus(FlowConstant.NODE_ERR);
        record.setErrReason(errMsg);
        taskFlowRecordService.updateById(record);
        if (node.getRetry()) {
            //支持手动重试机制则记录到节点状态可重试
            record.setRetry(Constant.YES);
            //记录并异常节点
            taskFlowRecordService.updateErrNode(record, node);
        }
        Integer errCount = record.getErrCount() == null ? 0 : record.getErrCount();
        //如果错误次数没有达到上限继续重试
        if (errCount < node.getAutoRetryNum()) {
            log.info("节点 {} 执行失败重试中参数为：{}", node.getName(), node.getNodeDto());
            record.setErrCount(errCount + 1);
            taskFlowRecordService.updateById(record);
            try {
                //将当前节点参数中的状态改为重试状态，避免数据的重复保存
                fillField(node.getNodeDto(), "retryStatus", true);
            } catch (Exception e) {

            }
            //重试当前节点
            return execute(node);
        } else {
            //添加节点执行异常 处理
            List<Node> errList = node.getErrList();
            if (errList != null && !errList.isEmpty()) {
                ExecutorService threadPool = Executors.newFixedThreadPool(2);
                for (Node child : errList) {
                    try {
                        //子节点因为共享了父节点的执行结果作为参数变量，会导致线程安全问题，所以序列化出多个参数实例可避免该问题
                        Class<?> clazz = Class.forName(child.getParameterType());
                        Object childDto = clazz.newInstance();
                        fillField(childDto, "status", false);
                        child.setNodeDto(childDto);
                    }catch (Exception e){
                        log.error("异常节点实例化参数失败：{}",e.getMessage());
                    }
                    threadPool.execute(() -> execute(child));
                }
            }
        }
        return false;
    }


    /**
     * 获取当前节点的执行状态（true，false）
     */
    public synchronized boolean getStatus(Object result) throws NoSuchFieldException, IllegalAccessException {
        boolean status = false;
        if (result instanceof NodeDto) {
            Field field = result.getClass().getField("status");
            field.setAccessible(true);
            status = field.getBoolean(result);
        }
        return status;
    }


    /**
     * 填充节点参数信息
     * 如果不加同步锁，则容易将节点添加参数时发生线程安全问题导致赋值错误（入节点参数与节点不符）
     * @param dto
     * @param fieldName
     * @param value
     * @return
     */
    public synchronized Object fillField(Object dto, String fieldName, Object value) throws NoSuchFieldException, IllegalAccessException {
        if (dto instanceof NodeDto) {
            Field  field = dto.getClass().getField(fieldName);
            field.setAccessible(true);
            field.set(dto, value);
        }
        return dto;
    }


    private String genKey(String flowName, String userId) {
        return "flow_" + flowName + "_" + userId;
    }

    /**
     * 获取正在执行的流程
     *
     * @return
     */
    public Collection<com.jd.umh.flow.engin.Flow> runningFlow() {
        Set<String> flowKeys = redisUtils.keys("flow");
        List<com.jd.umh.flow.engin.Flow> list = new ArrayList<>(flowKeys.size());
        for (String key : flowKeys) {
            com.jd.umh.flow.engin.Flow flow = redisUtils.get(key, Flow.class);
            list.add(flow);
        }
        return list;
    }

    public Map<String,String> flowEnginPool(){
        Map<String,String> map = new HashMap<>();
        int corePoolSize = threadPool.getCorePoolSize();
        int activeCount = threadPool.getActiveCount();
        long completedTaskCount = threadPool.getCompletedTaskCount();
        int poolSize = threadPool.getPoolSize();
        long taskCount = threadPool.getTaskCount();
        int size = threadPool.getQueue().size();
        map.put("corePoolSize",corePoolSize+"");
        map.put("activeCount",activeCount+"");
        map.put("completedTaskCount",completedTaskCount+"");
        map.put("poolSize",poolSize+"");
        map.put("taskCount",taskCount+"");
        map.put("size",size+"");
        return map;
    }


}
