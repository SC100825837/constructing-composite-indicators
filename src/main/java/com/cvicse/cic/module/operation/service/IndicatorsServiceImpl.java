package com.cvicse.cic.module.operation.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cvicse.cic.module.algorithm.service.AlgorithmService;
import com.cvicse.cic.module.datasource.service.DataIndicatorSystemDataService;
import com.cvicse.cic.module.datasource.service.DataIndicatorSystemNodeService;
import com.cvicse.cic.module.datasource.service.DataIndicatorSystemService;
import com.cvicse.cic.module.datasource.service.DataIndicatorSystemTreepathService;
import com.cvicse.cic.module.view.bean.CalcExecParamDTO;
import com.cvicse.cic.module.view.bean.CalcResultGraphDTO;
import com.cvicse.cic.module.view.bean.ECharts.CoordinateDTO;
import com.cvicse.cic.module.view.bean.GraphDTO;
import com.cvicse.cic.module.view.bean.ProcessResultDTO;
import com.cvicse.cic.module.datasource.bean.DataIndicatorSystemData;
import com.cvicse.cic.module.datasource.bean.DataIndicatorSystemNode;
import com.cvicse.cic.module.datasource.bean.DataIndicatorSystem;
import com.cvicse.cic.module.datasource.bean.DataIndicatorSystemTreepath;
import com.cvicse.cic.module.view.bean.GraphEdge;
import com.cvicse.cic.module.view.bean.GraphNode;
import com.cvicse.cic.util.AlgorithmConstants;
import com.cvicse.cic.util.AlgorithmUtil;
import com.cvicse.cic.util.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cvicse.cic.module.algorithm.bean.Algorithm;
import com.cvicse.cic.module.algorithm.bean.result.AlgorithmExecResult;
import com.cvicse.cic.module.algorithm.bean.result.FAMulValAnalysisPR;
import com.cvicse.cic.module.algorithm.bean.result.FactorAnalysisPR;
import com.cvicse.cic.handler.facade.AlgorithmFacade;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class IndicatorsServiceImpl {

    @Autowired
    private AlgorithmService algorithmService;

    @Autowired
    private DataIndicatorSystemDataService dataIndicatorSystemDataService;

    @Autowired
    private DataIndicatorSystemService dataIndicatorSystemService;

    @Autowired
    private DataIndicatorSystemNodeService dataIndicatorSystemNodeService;

    @Autowired
    private DataIndicatorSystemTreepathService dataIndicatorSystemTreepathService;

    /**
     * 基础图对象的缓存集合(平铺结构)，只包含基础结构的节点
     * 创建前端规定的节点对象集合，其中key为节点id，value为前端格式节点对象
     */
    private List<GraphNode> graphNodeList = new ArrayList<>();
    private List<GraphEdge> graphEdgeList = new ArrayList<>();

    /**
     * 记录当前最大的节点id，用来设置带数值的节点id
     */
    private Long currentMaxNodeId = 0L;

    /**
     * 节点类型
     */
    private int category = 0;

    /**
     * 指数图对象的缓存集合，既包含基础节点，也包含增加的指数节点
     */
    @Getter
    private List<GraphNode> indicatorGraphNodeList = new ArrayList<>();
    @Getter
    private List<GraphEdge> indicatorGraphEdgeList = new ArrayList<>();

    /**
     * 校验集合，创建节点时判断该节点或连线是否已创建
     */
    private Map<Object, String> checkExitMap = new HashMap<>();

    /**
     * 数据库查询的构建对象缓存
     */
    private List<DataIndicatorSystemData> dataIndicatorSystemDataList = new ArrayList<>();

    /**
     * 综合指数构建对象集合缓存
     */
    private Double[][] originDataArray;

    /**
     * 需要进行计算的目标 所在原始数据集的行数
     */
    private int targetObjLine;

    /**
     * 基础指标名称
     */
    private List<String> baseIndicatorName = new ArrayList<>();

    /**
     * 权重map，key为指标名称，value为权重值
     */
    private Map<String, Double> weightMap = new HashMap<>();

    /**
     * 基础指标值map，key为指标名称，value为指标值
     */
    private Map<String, Double> baseIndicatorValueMap = new HashMap<>();

    /**
     * 算法计算结果
     */
    private AlgorithmExecResult execResult;

    /**
     * 所有构建对象的综合指标值缓存
     */
    @Getter
    private Map<Long, LinkedHashMap<Long, Double>> allFrameObjectComInxMap = new HashMap<>();

    /**
     * 构建对象id缓存，如果实时数据和缓存不同则更新数据
     */
    private Long constructObjId = 0L;

    /**
     * 判断算法选择是否修改
     */
    private boolean ifAlgorithmModified = false;

    /**
     * 判断数据集是否修改
     */
    private boolean ifDataSetModified = false;

    /**
     * 获取基础的图数据模型
     *
     * @return
     */
    public GraphDTO getBaseGraph(Long dataIndicatorSystemId) {
        //先从缓存中取数据，如果没有数据则重新构建
        if (!graphNodeList.isEmpty() && !graphEdgeList.isEmpty()) {
            return new GraphDTO(graphNodeList, graphEdgeList);
        }

        // 查询最大id
        DataIndicatorSystemNode indicatorWithMaxId = dataIndicatorSystemNodeService.getBaseMapper()
                .selectOne(new QueryWrapper<DataIndicatorSystemNode>()
                        .eq("data_indicator_system_id", dataIndicatorSystemId)
                        .orderByDesc("id")
                        .last("limit 1"));
        if (indicatorWithMaxId != null) {
            this.currentMaxNodeId = indicatorWithMaxId.getId();
        }


        // 查询最大层级，把层级当做前端显示的类别
        DataIndicatorSystemNode indicatorWithMaxLevel = dataIndicatorSystemNodeService.getBaseMapper()
                .selectOne(new QueryWrapper<DataIndicatorSystemNode>()
                        .eq("data_indicator_system_id", dataIndicatorSystemId)
                        .orderByDesc("indicator_level")
                        .last("limit 1"));

        if (indicatorWithMaxLevel != null) {
            this.category = indicatorWithMaxLevel.getIndicatorLevel();
        }

        DataIndicatorSystem dataIndicatorSystem = dataIndicatorSystemService.getById(dataIndicatorSystemId);

        // 创建根节点
        GraphNode rootNode = new GraphNode();
        // 设置根节点的id
        rootNode.setId(++this.currentMaxNodeId);
        rootNode.setParentId(-1L);
        //设置根节点的类别
        rootNode.setCategory(++this.category);
        rootNode.getAttributes().put("name", "综合指数");
        this.graphNodeList.add(rootNode);

        // 查询父子级别  也就是相邻的层级结构
        List<DataIndicatorSystemTreepath> treepathList = dataIndicatorSystemTreepathService.getBaseMapper()
                .selectList(new QueryWrapper<DataIndicatorSystemTreepath>()
                        .eq("data_indicator_system_id", dataIndicatorSystemId)
                        .eq("path_depth", 1));

        // 遍历层级结构，根据结构查询节点
        for (DataIndicatorSystemTreepath treepath : treepathList) {
            // 查询后代位置的节点
            DataIndicatorSystemNode indicator = dataIndicatorSystemNodeService
                    .getOne(new QueryWrapper<DataIndicatorSystemNode>()
                            .eq("data_indicator_system_id", dataIndicatorSystemId)
                            .eq("id", treepath.getDescendant()));

            if (indicator == null) {
                continue;
            }
            // 获取该节点的 层级
            Integer indicatorLevel = indicator.getIndicatorLevel();
            // 如果层级为1，说明这个结构代表excel的前两列，也就是第一和第二指标
            if (indicatorLevel.equals(1)) {
                // 获取该结构的祖先级节点
                DataIndicatorSystemNode firstLevelIndicator = dataIndicatorSystemNodeService
                        .getOne(new QueryWrapper<DataIndicatorSystemNode>()
                                .eq("data_indicator_system_id", dataIndicatorSystemId)
                                .eq("id", treepath.getAncestor()));
                if (firstLevelIndicator == null) {
                    continue;
                }
                // 创建excel中各一级指标节点
                createBaseNode(firstLevelIndicator, rootNode.getId());
                // 创建综合指数节点和excel中各一级指标节点的连线
                createBaseEdge(new DataIndicatorSystemTreepath(rootNode.getId(), firstLevelIndicator.getId(), 1, dataIndicatorSystemId));

            }
            createBaseNode(indicator, treepath.getAncestor());
            createBaseEdge(treepath);
        }
        return new GraphDTO(this.graphNodeList, this.graphEdgeList);
    }

    /**
     * 创建节点
     */
    private void createBaseNode(DataIndicatorSystemNode indicator, Long parentId) {
        GraphNode graphNode = new GraphNode();
        graphNode.setId(indicator.getId());
        graphNode.setCategory(indicator.getIndicatorLevel());
        graphNode.setParentId(parentId);
        graphNode.getAttributes().put("name", indicator.getIndicatorName());
        if (check(indicator.getId())) {
            this.graphNodeList.add(graphNode);
        }
    }

    /**
     * 创建连线
     */
    private void createBaseEdge(DataIndicatorSystemTreepath treepath) {
        Long targetId = treepath.getAncestor();
        Long sourceId = treepath.getDescendant();
        GraphEdge graphEdge = new GraphEdge();
        graphEdge.setSourceId(sourceId);
        graphEdge.setTargetId(targetId);
        if (check(sourceId + "_" + targetId)) {
            this.graphEdgeList.add(graphEdge);
        }
    }

    /**
     * 检查该节点或者连线是否创建
     *
     * @param key
     * @return
     */
    private boolean check(Object key) {
        if (!checkExitMap.containsKey(key)) {
            checkExitMap.put(key, "");
            return true;
        } else {
            return false;
        }
    }

    public CalcResultGraphDTO calcHandler(CalcExecParamDTO calcExecParam) throws RuntimeException {
        if (graphNodeList.isEmpty() || graphEdgeList.isEmpty()) {
            throw new BusinessException("数据异常，请尝试刷新页面");
        }
        //初始化数据，然后执行处理算法
        Map<String, String> algorithmMap = null;
        try {
            algorithmMap = initAlgorithmAndConstructObj(calcExecParam);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);
            throw new BusinessException("数据初始化异常");
        }
        return handleDataAndAlgorithm(algorithmMap, calcExecParam.getTargetId(), calcExecParam.getDataIndicatorSystemId());
    }

    /**
     * 初始化算法数、和构造对象数据、数据集
     *
     * @param calcExecParam
     * @return
     */
    private Map<String, String> initAlgorithmAndConstructObj(CalcExecParamDTO calcExecParam) throws RuntimeException, JsonProcessingException {
        //获取所有算法的id
        Map<String, Long> algorithmIdMap = calcExecParam.getAlgorithms().getAllAlgorithmIds();
        // 根据算法id查询算法对象
        List<Algorithm> algorithms = algorithmService.listByIds(algorithmIdMap.values());

        //key是算法步骤名称，value是算法的全类名
        Map<String, String> algorithmMap = new HashMap<>();
        for (Algorithm algorithm : algorithms) {
            algorithmMap.put(algorithm.getStepName(), algorithm.getFullClassName() == null ? "" : algorithm.getFullClassName());
        }
        // 原始数据集没有修改过就用原始数据进行计算
        if (calcExecParam.getModifiedDataList() == null || calcExecParam.getModifiedDataList().length == 0) {
            //缓存中没有数据集的数据时从数据库取出并放入缓存
            if (this.originDataArray == null || this.originDataArray.length == 0) {
                List<DataIndicatorSystemData> dataIndicatorSystemDataList = dataIndicatorSystemDataService.list(
                        new QueryWrapper<DataIndicatorSystemData>()
                                .eq("data_indicator_system_id", calcExecParam.getDataIndicatorSystemId()));

                this.originDataArray = new Double[dataIndicatorSystemDataList.size()][];
                // 为了保证顺序和导入的excel一致，进行排序
                this.dataIndicatorSystemDataList = dataIndicatorSystemDataList.stream()
                        .sorted(Comparator.comparingInt(DataIndicatorSystemData::getBelongColumnIndex))
                        .collect(Collectors.toList());
                // 创建原始数据集二维数组
                for (int i = 0; i < this.dataIndicatorSystemDataList.size(); i++) {
                    if (this.dataIndicatorSystemDataList.get(i).getId().equals(calcExecParam.getTargetId())) {
                        this.targetObjLine = i;
                    }
                    this.originDataArray[i] = new ObjectMapper().readValue(this.dataIndicatorSystemDataList.get(i).getDataValue(), Double[].class);
                }
            } else {
                for (int i = 0; i < this.dataIndicatorSystemDataList.size(); i++) {
                    if (this.dataIndicatorSystemDataList.get(i).getId().equals(calcExecParam.getTargetId())) {
                        this.targetObjLine = i;
                    }
                }
            }
        } else {
            //拿到修改后的数据集
            this.originDataArray = calcExecParam.getModifiedDataList();
            this.ifDataSetModified = true;
        }
        return algorithmMap;
    }

    /**
     * 进行综合指数计算
     *
     * @param algorithmMap 每一步的算法对象
     * @return
     */
    public CalcResultGraphDTO handleDataAndAlgorithm(Map<String, String> algorithmMap, Long targetId, Long DataIndicatorSystemId) {
        //判断数据集是否修改,没修改直接用缓存数据，修改了就重新计算
        //TODO 切换了算法也需要重新计算
        if (this.execResult == null || ifDataSetModified || ifAlgorithmModified) {
            this.allFrameObjectComInxMap.remove(DataIndicatorSystemId);
            //通过算法门面执行算法计算
            this.execResult = AlgorithmFacade.calculate(algorithmMap, this.originDataArray);
        }

        //缺失值插补的结果
        Double[][] missDataImputationArr = execResult.getMissDataImputation();

        //得到权重计算的最终结果，即权重值数组
        Double[] baseIndicatorWeight = execResult.getWeightingAndAggregation().getFinalResult()[0];

        //初始化综合指标
        double compositeIndicator = 0;
        LinkedHashMap<Long, Double> specifiedFrameObjectInxMap = this.allFrameObjectComInxMap.get(DataIndicatorSystemId);
        if (specifiedFrameObjectInxMap == null || specifiedFrameObjectInxMap.isEmpty()) {
            //计算所有构建对象的综合指标值
            specifiedFrameObjectInxMap = calcAllConstructTarget(DataIndicatorSystemId, missDataImputationArr, baseIndicatorWeight);
        }
        compositeIndicator = specifiedFrameObjectInxMap.get(targetId);
        /*//计算综合指标数值
        for (int i = 0; i < targetLineData.length; i++) {
            compositeIndicator += targetLineData[i] * baseIndicatorWeight[i];
        }
        //处理小数点位数
        compositeIndicator = handleFractional(2, compositeIndicator);*/

        // 获取基础指标所在的excel列
        int baseIndicatorNodeColumn = getBaseIndicatorNodeColumn(DataIndicatorSystemId);
        // 按顺序获取所有基础指标集合
        List<DataIndicatorSystemNode> baseIndicatorList = getBaseIndicatorList(DataIndicatorSystemId, baseIndicatorNodeColumn);


        // 找到缺失值插补计算之后的构建对象数据
        Double[] targetLineData = missDataImputationArr[this.targetObjLine];
        // key是节点id， value是节点的基础数据值（经过缺失值插补计算的）
        Map<Long, Double> targetDataMap = new HashMap<>(baseIndicatorList.size());
        Map<Long, Double> weightMap = new HashMap<>(baseIndicatorList.size());
        this.baseIndicatorName.clear();
        for (int i = 0; i < baseIndicatorList.size(); i++) {
            this.baseIndicatorName.add(baseIndicatorList.get(i).getIndicatorName());
            targetDataMap.put(baseIndicatorList.get(i).getId(), targetLineData[i]);
            weightMap.put(baseIndicatorList.get(i).getId(), baseIndicatorWeight[i]);
        }
        //构建带有指标值的图数据
        constructIndicatorGraph(compositeIndicator, targetId, baseIndicatorNodeColumn, targetDataMap, weightMap);

        CalcResultGraphDTO calcResultGraphDTO = new CalcResultGraphDTO();
        calcResultGraphDTO.setAlgorithmExecResult(execResult);
        calcResultGraphDTO.setCompositeIndicator(compositeIndicator);
        calcResultGraphDTO.getCompIndGraphNode().addAll(indicatorGraphNodeList);
        calcResultGraphDTO.getCompIndGraphEdge().addAll(indicatorGraphEdgeList);
        return calcResultGraphDTO;
    }

    /**
     * 计算所有构建对象的综合指标值
     * @param missDataImputationArr
     * @param baseIndicatorWeight
     */
    private LinkedHashMap<Long, Double> calcAllConstructTarget(Long DataIndicatorSystemId, Double[][] missDataImputationArr, Double[] baseIndicatorWeight) {
        LinkedHashMap<Long, Double> allTargetInxMap = new LinkedHashMap<>();
        for (int i = 0; i < this.dataIndicatorSystemDataList.size(); i++) {
            double oneCompositeIndicator = 0D;
            for (int j = 0; j < baseIndicatorWeight.length; j++) {
                oneCompositeIndicator += missDataImputationArr[i][j] * baseIndicatorWeight[j];
            }
            allTargetInxMap.put(this.dataIndicatorSystemDataList.get(i).getId(), AlgorithmUtil.handleFractional(2, oneCompositeIndicator));
        }
        this.allFrameObjectComInxMap.put(DataIndicatorSystemId, allTargetInxMap);
        return allTargetInxMap;
    }

    /**
     * 按顺序获取所有基础指标集合
     * @param DataIndicatorSystemId
     * @return
     */
    private List<DataIndicatorSystemNode> getBaseIndicatorList(Long DataIndicatorSystemId, int baseIndicatorNodeColumn) {
        // 虽然理论上插入数据库是按照excel顺序来插入的，但是稳妥起见还是排个序
        return dataIndicatorSystemNodeService.list(new QueryWrapper<DataIndicatorSystemNode>()
                .eq("data_indicator_system_id", DataIndicatorSystemId)
                .eq("indicator_level", baseIndicatorNodeColumn).eq("head_flag", 0))
                .stream()
                .sorted(Comparator.comparingLong(DataIndicatorSystemNode::getId))
                .collect(Collectors.toList());
    }

    /**
     * 获取基础指标所在的excel列
     * @param DataIndicatorSystemId
     * @return
     */
    private int getBaseIndicatorNodeColumn(Long DataIndicatorSystemId) {
        // 基础指标节点所在的列
        int baseIndicatorNodeColumn = 0;
        DataIndicatorSystem DataIndicatorSystem = dataIndicatorSystemService.getById(DataIndicatorSystemId);
        if (DataIndicatorSystem != null) {
            // 基础指标节点所在的列 和 数据列 相邻
//            baseIndicatorNodeColumn = DataIndicatorSystem.getDataFirstColumn() - 1;
        }
        return baseIndicatorNodeColumn;
    }

    /**
     * 构建带有指标值的图数据
     *
     * @param compositeIndicator
     */
    private void constructIndicatorGraph(Double compositeIndicator, Long targetId, int baseIndicatorNodeColumn, Map<Long, Double> targetDataMap, Map<Long, Double> weightMap) {
        //如果对象一样，就判断数据集是否修改,没修改直接用缓存数据
        if (constructObjId.equals(targetId) && !ifDataSetModified) {
            return;
        }
        if (!indicatorGraphNodeList.isEmpty() && !indicatorGraphEdgeList.isEmpty()) {
            indicatorGraphNodeList = new ArrayList<>();
            indicatorGraphEdgeList = new ArrayList<>();
        }
        indicatorGraphNodeList.addAll(graphNodeList);
        indicatorGraphEdgeList.addAll(graphEdgeList);
        constructObjId = targetId;

        for (GraphNode graphNode : graphNodeList) {
            //找到基础指标节点, 它的类别值是基础指标所在的excel列下标
            if (graphNode.getCategory() == baseIndicatorNodeColumn) {
                //创建指标节点，并设置属性
                GraphNode baseIndicatorDataNode = new GraphNode();
                baseIndicatorDataNode.setId(++this.currentMaxNodeId);
                baseIndicatorDataNode.getAttributes().put("indicatorValue", targetDataMap.get(graphNode.getId()));
                baseIndicatorDataNode.setCategory(this.category + 1);
                baseIndicatorDataNode.setLbName("基础指标值");
                baseIndicatorDataNode.setParentId(graphNode.getId());

                //创建权重节点，并设置属性
                GraphNode weightNode = new GraphNode();
                weightNode.setId(++this.currentMaxNodeId);
                weightNode.getAttributes().put("indicatorValue", weightMap.get(graphNode.getId()));
                weightNode.setCategory(this.category + 2);
                weightNode.setLbName("权重值");
                weightNode.setParentId(graphNode.getId());

                //创建连线，并设置属性，基础指标节点由指标值指向 通用指标名称
                GraphEdge indicatorGraphEdge = new GraphEdge();
                indicatorGraphEdge.setSourceId(baseIndicatorDataNode.getId());
                indicatorGraphEdge.setTargetId(graphNode.getId());
                //创建连线，并设置属性，权重节点 指向 通用指标名称
                GraphEdge weightGraphEdge = new GraphEdge();
                weightGraphEdge.setSourceId(weightNode.getId());
                weightGraphEdge.setTargetId(graphNode.getId());

                indicatorGraphNodeList.add(baseIndicatorDataNode);
                indicatorGraphNodeList.add(weightNode);
                //添加到新创建的连线放置到连线集合缓存中
                indicatorGraphEdgeList.add(indicatorGraphEdge);
                indicatorGraphEdgeList.add(weightGraphEdge);
            }
        }
        //创建综合指标值节点，并设置属性
        GraphNode compIndGraphNode = new GraphNode();
        compIndGraphNode.setId(++this.currentMaxNodeId);
        compIndGraphNode.getAttributes().put("indicatorValue", compositeIndicator);
        compIndGraphNode.setLbName("综合指标值");
        compIndGraphNode.setCategory(this.category + 3);
        compIndGraphNode.setParentId(-1L);
        //创建连线，并设置属性，综合指标值节点由 指标值 指向 通用指标名称
        GraphEdge graphEdge = new GraphEdge();
        graphEdge.setSourceId(compIndGraphNode.getId());
        graphEdge.setTargetId(graphNodeList.get(0).getId());

        //将综合指标值节点放入缓存
        indicatorGraphNodeList.add(compIndGraphNode);
        //将新创建的综合指标值和通用综合指标名称的连线关系放入缓存
        indicatorGraphEdgeList.add(graphEdge);

        this.ifDataSetModified = false;
    }

    /**
     * 获取原始数据集
     *
     * @return
     */
    public Double[][] getOriginDataArray(Long targetId, Long DataIndicatorSystemId) {
        //缓存中没有数据集的数据时从数据库取出并放入缓存
        if (this.originDataArray == null || this.originDataArray.length == 0) {
            // TODO 架构对象id
            List<DataIndicatorSystemData> dataIndicatorSystemDataList = dataIndicatorSystemDataService.list(
                    new QueryWrapper<DataIndicatorSystemData>()
                            .eq("data_indicator_system_id", DataIndicatorSystemId));

            this.originDataArray = new Double[dataIndicatorSystemDataList.size()][];
            // 为了保证顺序和导入的excel一致，进行排序
            dataIndicatorSystemDataList = dataIndicatorSystemDataList.stream()
                    .sorted(Comparator.comparingInt(DataIndicatorSystemData::getBelongColumnIndex))
                    .collect(Collectors.toList());
            // 创建原始数据集二维数组
            for (int i = 0; i < dataIndicatorSystemDataList.size(); i++) {
                try {
                    this.originDataArray[i] = new ObjectMapper().readValue(dataIndicatorSystemDataList.get(i).getDataValue(), Double[].class);
                } catch (JsonProcessingException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
        return this.originDataArray;
    }

    /**
     * 拿到计算过程数据,封装对象并返回
     */
    public ProcessResultDTO getProcessData(Long DataIndicatorSystemId) {

        if (this.execResult == null) {
            return null;
        }
        return createWebDTO(DataIndicatorSystemId);
    }

    /**
     * 拿到计算过程数据,封装对象并返回
     */
    private ProcessResultDTO createWebDTO(Long DataIndicatorSystemId) {
        //创建过程结果前端封装对象
        ProcessResultDTO processResultDTO = new ProcessResultDTO();

        //获取原始数据集
        List<DataIndicatorSystemData> targetNameList = dataIndicatorSystemDataService.list(new QueryWrapper<DataIndicatorSystemData>()
                .select("target_name")
                .eq("data_indicator_system_id", DataIndicatorSystemId));
        List<Map<String, Double>> originDataList = new ArrayList<>();
        for (int i = 0; i < this.originDataArray.length; i++) {
            Map<String, Double> row = new LinkedHashMap<>();
            for (int j = 0; j < this.originDataArray[i].length; j++) {
                row.put(this.baseIndicatorName.get(j), this.originDataArray[i][j]);
            }
            originDataList.add(row);
        }
        processResultDTO.getOriginalData().put(AlgorithmConstants.FIRST_LEVEL_TITLE, AlgorithmConstants.ORIGIN_DATA_SET_NAME_ZH);
        processResultDTO.getOriginalData().put("isContainPR", false);
        processResultDTO.getOriginalData().put("data", originDataList);

        //缺失值插补
        Double[][] missDataImputationArr = this.execResult.getMissDataImputation();
        //创建新的集合，用来存储缺失值插补算法返回的数据
        List<Map<String, Double>> missDataImputationList = new ArrayList<>();
        for (int i = 0; i < missDataImputationArr.length; i++) {
            Map<String, Double> row = new LinkedHashMap<>();
            for (int j = 0; j < missDataImputationArr[i].length; j++) {
                row.put(this.baseIndicatorName.get(j), missDataImputationArr[i][j]);
            }
            missDataImputationList.add(row);
        }
        processResultDTO.getMissDataImputation().put(AlgorithmConstants.FIRST_LEVEL_TITLE, AlgorithmConstants.MISS_DATA_IMPUTATION_NAME_ZH);
        processResultDTO.getMissDataImputation().put("isContainPR", false);
        processResultDTO.getMissDataImputation().put("data", missDataImputationList);

        //多变量分析
        //拿到多变量分析计算结果
        FAMulValAnalysisPR multivariateAnalysisPR = (FAMulValAnalysisPR) this.execResult.getMultivariateAnalysis();
        Map<String, Object> multivariateAnalysisResultMap = new HashMap<>();
        Double[][] correlationMatrix = multivariateAnalysisPR.getCorrelationMatrix();
        //创建矩阵图数据对象
        CoordinateDTO correlationMatrixCoordinate = new CoordinateDTO();
        List<String> axisData = this.baseIndicatorName;
        //设置x轴
        correlationMatrixCoordinate.setXAxis(axisData);
        //设置y轴
        correlationMatrixCoordinate.setYAxis(axisData);
        //设置数据
        List<List<Double>> correlationMatrixData = new ArrayList<>();
        for (int i = 0; i < correlationMatrix.length; i++) {
            for (int j = 0; j < correlationMatrix[i].length; j++) {
                List<Double> unitData = new ArrayList<>();
                unitData.add((double) j);
                unitData.add((double) i);
                unitData.add(AlgorithmUtil.handleFractional(2, correlationMatrix[i][j]));
                correlationMatrixData.add(unitData);
            }
        }
        correlationMatrixCoordinate.setData(correlationMatrixData);
        correlationMatrixCoordinate.setMaxValue(1);
        correlationMatrixCoordinate.setMinValue(-1);
        correlationMatrixCoordinate.setTitle("相关性矩阵");
        multivariateAnalysisResultMap.put("correlationMatrix", correlationMatrixCoordinate);
        processResultDTO.getMultivariateAnalysis().put(AlgorithmConstants.FIRST_LEVEL_TITLE, AlgorithmConstants.MULTI_VARIATE_ANALYSIS_NAME_ZH);
        processResultDTO.getMultivariateAnalysis().put("isContainPR", true);
        processResultDTO.getMultivariateAnalysis().put("data", multivariateAnalysisResultMap);

        //标准化
        Double[][] normalisationArr = this.execResult.getNormalisation();
        //创建新的集合，用来存储标准化算法返回的数据
        List<Map<String, Double>> normalisationList = new ArrayList<>();
        for (int i = 0; i < normalisationArr.length; i++) {
            Map<String, Double> row = new LinkedHashMap<>();
            for (int j = 0; j < normalisationArr[i].length; j++) {
                row.put(this.baseIndicatorName.get(j), normalisationArr[i][j]);
            }
            normalisationList.add(row);
        }
        processResultDTO.getNormalisation().put(AlgorithmConstants.FIRST_LEVEL_TITLE, AlgorithmConstants.NORMALISATION_NAME_ZH);
        processResultDTO.getNormalisation().put("isContainPR", false);
        processResultDTO.getNormalisation().put("data", normalisationList);

        //权重和聚合
        //从计算结果中取权重和聚合算法的结果
        FactorAnalysisPR weightingAndAggregation = (FactorAnalysisPR) this.execResult.getWeightingAndAggregation();
        Map<String, Object> weightingAndAggregationResultMap = new HashMap<>();
        //取得权重和聚合算法中的负载因子加载矩阵
        Double[][] rotatedFactorLoadingsMatrix = weightingAndAggregation.getRotatedFactorLoadingsMatrix();
        //创建矩阵图数据对象
        CoordinateDTO rotatedFactorLoadingsMatrixCoordinate = new CoordinateDTO();
        //设置x轴
        rotatedFactorLoadingsMatrixCoordinate.setXAxis(Arrays.asList("因子1", "因子2", "因子3", "因子4"));
        //设置y轴
        rotatedFactorLoadingsMatrixCoordinate.setYAxis(axisData);
        //设置数据
        List<List<Double>> rotatedFactorLoadingsMatrixData = new ArrayList<>();
        for (int i = 0; i < rotatedFactorLoadingsMatrix.length; i++) {
            for (int j = 0; j < rotatedFactorLoadingsMatrix[i].length; j++) {
                List<Double> unitData = new ArrayList<>();
                unitData.add((double) j);
                unitData.add((double) i);
                unitData.add(AlgorithmUtil.handleFractional(2, rotatedFactorLoadingsMatrix[i][j]));
                rotatedFactorLoadingsMatrixData.add(unitData);
            }
        }
        rotatedFactorLoadingsMatrixCoordinate.setData(rotatedFactorLoadingsMatrixData);
        //设置颜色上下限的值
        rotatedFactorLoadingsMatrixCoordinate.setMinValue(-1);
        rotatedFactorLoadingsMatrixCoordinate.setMaxValue(1);
        rotatedFactorLoadingsMatrixCoordinate.setTitle("旋转因子载荷矩阵");
        weightingAndAggregationResultMap.put("rotatedFactorLoadingsMatrix", rotatedFactorLoadingsMatrixCoordinate);

        //取得权重和聚合算法中的特征值、方差百分比、累计方差
        Double[][] eigenvalueArr = weightingAndAggregation.getEigenvalues();
        //创建矩阵图数据对象
        CoordinateDTO eigenvalueCoordinate = new CoordinateDTO();
        //设置x轴
        eigenvalueCoordinate.setXAxis(Arrays.asList("特征值", "方差(%)", "累积方差(%)"));
        //设置y轴
        eigenvalueCoordinate.setYAxis(axisData);
        //设置数据
        List<List<Double>> eigenvalueData = new ArrayList<>();
        for (int i = 0; i < eigenvalueArr.length; i++) {
            for (int j = 0; j < eigenvalueArr[i].length; j++) {
                List<Double> unitData = new ArrayList<>();
                unitData.add((double) j);
                unitData.add((double) i);
                unitData.add(AlgorithmUtil.handleFractional(2, eigenvalueArr[i][j]));
                eigenvalueData.add(unitData);
            }
        }
        eigenvalueCoordinate.setData(eigenvalueData);
        //设置颜色上下限的值
        eigenvalueCoordinate.setMinValue(-1);
        eigenvalueCoordinate.setMaxValue(100);
        eigenvalueCoordinate.setTitle("特征值");
        weightingAndAggregationResultMap.put("eigenvalues", eigenvalueCoordinate);

        //取得权重和聚合算法中的指标权重
        Double[] indicatorWeight = weightingAndAggregation.getIndicatorWeight()[0];
        //创建矩阵图数据对象
        CoordinateDTO indicatorWeightCoordinate = new CoordinateDTO();
        //设置x轴
        indicatorWeightCoordinate.setXAxis(Collections.singletonList("权重"));
        //设置y轴
        indicatorWeightCoordinate.setYAxis(axisData);
        //设置数据
        List<List<Double>> indicatorWeightData = new ArrayList<>();
        for (int i = 0; i < indicatorWeight.length; i++) {
            List<Double> unitData = new ArrayList<>();
            unitData.add((double) i);
            unitData.add((double) 0);
            unitData.add(AlgorithmUtil.handleFractional(2, indicatorWeight[i]));
            indicatorWeightData.add(unitData);
        }
        indicatorWeightCoordinate.setData(indicatorWeightData);
        //设置颜色上下限的值
        indicatorWeightCoordinate.setMinValue(0);
        indicatorWeightCoordinate.setMaxValue(1);
        indicatorWeightCoordinate.setTitle("权重");
        weightingAndAggregationResultMap.put("indicatorWeight", indicatorWeightCoordinate);
        processResultDTO.getWeightingAndAggregation().put(AlgorithmConstants.FIRST_LEVEL_TITLE, AlgorithmConstants.WEIGHTING_AND_AGGREGATION_NAME_ZH);
        processResultDTO.getWeightingAndAggregation().put("isContainPR", true);
        processResultDTO.getWeightingAndAggregation().put("data", weightingAndAggregationResultMap);

        return processResultDTO;
    }

    /**
     * 计算基础指标值修改之后的综合指标值
     *
     * @param mdBaseIndicatorMap
     * @return
     */
    public Double calcModifyBaseIndicator(Map<String, Double> mdBaseIndicatorMap) {
        //TODO 现在做的是没有标准化直接进行计算的结果，需要根据选择的标准化算法进行计算
        Double mdCompositeIndicator = (double) 0;
        for (String modifyName : mdBaseIndicatorMap.keySet()) {
            for (String baseIndicatorName : baseIndicatorValueMap.keySet()) {
                if (modifyName.equals(baseIndicatorName)) {
                    continue;
                }
                mdCompositeIndicator += baseIndicatorValueMap.get(baseIndicatorName) * weightMap.get(modifyName);
            }
            mdCompositeIndicator += mdBaseIndicatorMap.get(modifyName) * weightMap.get(modifyName);
        }
        return AlgorithmUtil.handleFractional(2, mdCompositeIndicator);
    }

    /**
     * 清楚所有缓存，重置数据
     *
     * @return
     */
    public boolean resetData() {
        this.allFrameObjectComInxMap.clear();
        switchFrameObj();
        return true;
    }

    /**
     * 单纯切换架构对象，缓存中所有架构对象的综合指数不需要删除
     * 除非更换了算法，或者重新点击了计算，这个时候在计算综合指数的方法中会将 综合指数的缓存清空
     */
    public void switchFrameObj() {
        this.graphNodeList.clear();
        this.graphEdgeList.clear();
        this.indicatorGraphNodeList.clear();
        this.indicatorGraphEdgeList.clear();

        this.currentMaxNodeId = 0L;
        this.category = 0;
        this.checkExitMap.clear();

        this.dataIndicatorSystemDataList.clear();
        this.originDataArray = null;
        this.targetObjLine = 0;
        this.baseIndicatorName = new ArrayList<>();
        this.weightMap.clear();
        this.baseIndicatorValueMap.clear();

        this.execResult = null;
        this.constructObjId = 0L;
        this.ifDataSetModified = false;
    }
}
