package paasta.delivery.pipeline.api.job;

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import paasta.delivery.pipeline.api.cf.info.CfInfoService;
import paasta.delivery.pipeline.api.common.*;
import paasta.delivery.pipeline.api.credential.CredentialsService;
import paasta.delivery.pipeline.api.exception.TriggerException;
import paasta.delivery.pipeline.api.job.config.JobConfig;
import paasta.delivery.pipeline.api.job.template.JobTemplateService;
import paasta.delivery.pipeline.api.repository.RepositoryService;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * paastaDeliveryPipelineApi
 * paasta.delivery.pipeline.api.job
 *
 * @author REX
 * @version 1.0
 * @since 5 /8/2017
 */
@Service
public class JobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobService.class);
    private static final String REQ_URL = "/jobs";
    private static final String REQ_PIPELINES_URL = "/pipelines/";
    private static final String REQ_HISTORY_URL = "/histories";
    private static final String REQ_JOB_HISTORY_URL = "/job-histories";
    private static final String REQ_FILE_URL = "/file";
    private static final String REQ_SERVICE_INSTANCES_URL = "/serviceInstance/";
    private static final String CF_API_URL_SPLIT_STRING = "api";
    private static final String URL_PROTOCOL_STRING = "http://";
    private static final String GROUP_ORDER_STRING = "groupOrder";
    private static final String JOB_ORDER_STRING = "jobOrder";

    private final CommonService commonService;
    private final RestTemplateService restTemplateService;
    private final JobTemplateService jobTemplateService;
    private final JobBuiltFileService jobBuiltFileService;
    private final CredentialsService credentialsService;
    private final RepositoryService repositoryService;
    private final CfInfoService cfInfoService;


    /**
     * Instantiates a new Job service.
     *
     * @param commonService       the common service
     * @param restTemplateService the rest template service
     * @param jobTemplateService  the job template service
     * @param jobBuiltFileService the job built file service
     * @param credentialsService  the credentials service
     * @param repositoryService   the repository service
     * @param cfInfoService       the cf info service
     */
    @Autowired
    public JobService(CommonService commonService,
                      RestTemplateService restTemplateService,
                      JobTemplateService jobTemplateService,
                      JobBuiltFileService jobBuiltFileService,
                      CredentialsService credentialsService,
                      RepositoryService repositoryService, CfInfoService cfInfoService) {
        this.commonService = commonService;
        this.restTemplateService = restTemplateService;
        this.jobTemplateService = jobTemplateService;
        this.jobBuiltFileService = jobBuiltFileService;
        this.credentialsService = credentialsService;
        this.repositoryService = repositoryService;
        this.cfInfoService = cfInfoService;
    }


    // Get Job List from DB
    private List procGetJobList(Long pipelineId) {
        String reqUrl = REQ_PIPELINES_URL + pipelineId + REQ_URL;
        return restTemplateService.send(Constants.TARGET_COMMON_API, reqUrl, HttpMethod.GET, null, List.class);
    }


    // Get Job Detail from DB
    private CustomJob procGetJobDetail(Long id) {
        return restTemplateService.send(Constants.TARGET_COMMON_API, REQ_URL + "/" + id, HttpMethod.GET, null, CustomJob.class);
    }


    // Get Job Max Group Order from DB
    private int procGetJobMaxGroupOrder(Long pipelineId) {
        String reqUrl = REQ_PIPELINES_URL + pipelineId + "/max-job-group-order";
        return restTemplateService.send(Constants.TARGET_COMMON_API, reqUrl, HttpMethod.GET, null, Integer.class);
    }


    // Create Job to DB
    private CustomJob procCreateJobToDb(CustomJob customJob) {
        return restTemplateService.send(Constants.TARGET_COMMON_API, REQ_URL, HttpMethod.POST, customJob, CustomJob.class);
    }


    // Update Job to DB
    private CustomJob procUpdateJobToDb(CustomJob customJob) {
        return restTemplateService.send(Constants.TARGET_COMMON_API, REQ_URL, HttpMethod.PUT, customJob, CustomJob.class);
    }


    // Create Job History to DB
    private JobHistory procCreateJobHistoryToDb(JobHistory jobHistory) {
        return restTemplateService.send(Constants.TARGET_COMMON_API, REQ_JOB_HISTORY_URL, HttpMethod.POST, jobHistory, JobHistory.class);
    }


    // Update Job History to DB
    private void procUpdateJobHistoryToDb(JobHistory jobHistory) {
        restTemplateService.send(Constants.TARGET_COMMON_API, REQ_JOB_HISTORY_URL, HttpMethod.PUT, jobHistory, JobHistory.class);
    }


    // Get Service Instances Detail from DB
    private ServiceInstances procGetServiceInstances(String serviceInstancesId) {
        return restTemplateService.send(Constants.TARGET_COMMON_API, REQ_SERVICE_INSTANCES_URL + serviceInstancesId, HttpMethod.GET, null, ServiceInstances.class);
    }


    // Set Job Name
    private String procSetJobName(Long pipelineId, String jobName) {
        String resultJobName = jobName;

        // CHECK EXISTED JOB NAME
        if (procCheckExistedJobName(pipelineId, jobName)) {

            // RENAME
            int jobNamLength = jobName.length();
            String checkString1 = jobName.substring(jobNamLength - 1);
            String checkString10 = jobName.substring(jobNamLength - 2, jobNamLength - 1);
            String checkString100 = jobName.substring(jobNamLength - 3, jobNamLength - 2);

            if (procCheckIsNumeric(checkString1)) {
                if (procCheckIsNumeric(checkString10)) {
                    if (procCheckIsNumeric(checkString100)) {
                        resultJobName = jobName.substring(0, jobNamLength - 3) + (Integer.parseInt(checkString100 + checkString10 + checkString1) + 1);
                    } else {
                        resultJobName = jobName.substring(0, jobNamLength - 2) + (Integer.parseInt(checkString10 + checkString1) + 1);
                    }
                } else {
                    resultJobName = jobName.substring(0, jobNamLength - 1) + (Integer.parseInt(checkString1) + 1);
                }
            } else {
                resultJobName = jobName + "-1";
            }

            // CHECK EXISTED JOB NAME
            resultJobName = procSetJobName(pipelineId, resultJobName);
        }

        return resultJobName;
    }


    // Check Existed Job Name
    private boolean procCheckExistedJobName(Long pipelineId, String jobName) {
        String reqUrl = REQ_PIPELINES_URL + pipelineId + "/job-names/" + jobName;
        return restTemplateService.send(Constants.TARGET_COMMON_API, reqUrl, HttpMethod.GET, null, Integer.class) > 0;
    }


    // Check Is Numeric
    private boolean procCheckIsNumeric(String reqString) {
        return reqString.matches("^[0-9]*$");
    }


    /**
     * Create job custom job.
     *
     * @param customJob the custom job
     * @return the custom job
     * @throws IOException the io exception
     */
    CustomJob createJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        String reqJobType = customJob.getJobType();

        // SET JOB GROUP ORDER IF CHECKING NEW WORK GROUP
        if (Constants.USE_YN_Y.equals(customJob.getNewWorkGroupYn())) {
            // GET JOB MAX GROUP ORDER FROM DATABASE
            customJob.setGroupOrder(procGetJobMaxGroupOrder(customJob.getPipelineId()) + 1);
            customJob.setJobOrder(1);
        }

        // GET SERVICE INSTANCES DETAIL FROM DATABASE
        // SET CI SERVER URL
        customJob.setCiServerUrl(procGetServiceInstances(customJob.getServiceInstancesId()).getCiServerUrl());

        // BUILD JOB
        if (String.valueOf(JobType.BUILD).equals(reqJobType)) {
            resultModel = procCreateBuildJob(customJob);
        }

        // TEST JOB
        if (String.valueOf(JobType.TEST).equals(reqJobType)) {
            resultModel = procCreateTestJob(customJob);
        }

        // DEPLOY JOB
        if (String.valueOf(JobType.DEPLOY).equals(reqJobType)) {
            resultModel = procCreateDeployJob(customJob);
        }

        // SET JOB ORDER IN PIPELINE
        procSetJobOrder(resultModel, OperationType.INCREASE);

        return resultModel;
    }


    /**
     * Update job custom job.
     *
     * @param customJob the custom job
     * @return the custom job
     * @throws IOException the io exception
     */
    CustomJob updateJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        String reqJobType = customJob.getJobType();

        // GET SERVICE INSTANCES DETAIL FROM DATABASE
        // SET CI SERVER URL
        customJob.setCiServerUrl(procGetServiceInstances(customJob.getServiceInstancesId()).getCiServerUrl());

        // BUILD JOB
        if (String.valueOf(JobType.BUILD).equals(reqJobType)) {
            resultModel = procUpdateBuildJob(customJob);
        }

        // TEST JOB
        if (String.valueOf(JobType.TEST).equals(reqJobType)) {
            resultModel = procUpdateTestJob(customJob);
        }

        // DEPLOY JOB
        if (String.valueOf(JobType.DEPLOY).equals(reqJobType)) {
            resultModel = procUpdateDeployJob(customJob);
        }

        return resultModel;
    }


    // Create Build Job
    private CustomJob procCreateBuildJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        String jobGuid = procSetJobGuid(JobType.BUILD);
        customJob.setJobGuid(jobGuid);

        // CREATE CREDENTIALS TO CI SERVER
        credentialsService.createCredentials(customJob);

        // CREATE BUILD JOB TO CI SERVER
        commonService.procGetCiServer(customJob.getCiServerUrl()).createJob(jobGuid, jobTemplateService.getBuildJobTemplate(customJob), true);

        // SET JOB NAME :: CHECK FROM DATABASE
        customJob.setJobName(procSetJobName(customJob.getPipelineId(), customJob.getJobName()));

        // INSERT BUILD JOB TO DATABASE
        resultModel = procCreateJobToDb(customJob);

        // GET REPOSITORY COMMIT REVISION
        CustomJob repositoryInfoModel = repositoryService.getRepositoryInfo(String.valueOf(resultModel.getId()));

        // SET PARAM :: UPDATE JOB DETAIL
        resultModel.setRepositoryCommitRevision(repositoryInfoModel.getRepositoryCommitRevision());
        resultModel.setRepositoryAccountPassword(customJob.getRepositoryAccountPassword());

        // UPDATE BUILD JOB TO DATABASE
        procUpdateJobToDb(resultModel);

        resultModel.setJobGuid(jobGuid);
        return resultModel;
    }


    // Set Job Guid
    private String procSetJobGuid(Enum reqJobType) {
        return "DP-" + reqJobType + "-" + UUID.randomUUID().toString() + "-" + ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }


    // Set Job Order
    private void procSetJobOrder(CustomJob customJob, Enum requestOperation) {
        Long currentJobId = customJob.getId();
        int currentGroupOrder = customJob.getGroupOrder();
        int currentJobOrder = customJob.getJobOrder();
        int operationCount = 1;

        if (OperationType.DECREASE.equals(requestOperation)) {
            operationCount = -1;
        }

        // GET JOB LIST BY PIPELINE ID AND GROUP ORDER
        // LOOP
        // IF CURRENT JOB ORDER <= JOB ORDER OF RESULT JOB LIST
        // UPDATE JOB ORDER TO JOB OF RESULT JOB LIST
        for (Object aResultList : procGetJobList(customJob.getPipelineId())) {
            Map<String, Object> map = (Map<String, Object>) aResultList;
            int tempJobId = (int) map.get("id");
            Long resultJobId = (long) tempJobId;
            int resultGroupOrder = (int) map.get(GROUP_ORDER_STRING);
            int resultJobOrder = (int) map.get(JOB_ORDER_STRING);

            if ((currentGroupOrder == resultGroupOrder) && (currentJobOrder <= resultJobOrder) && (!currentJobId.equals(resultJobId))) {
                // GET JOB DETAIL FROM DATABASE
                CustomJob jobDetail = procGetJobDetail(resultJobId);
                jobDetail.setJobOrder(jobDetail.getJobOrder() + operationCount);

                // UPDATE JOB TO DATABASE
                procUpdateJobToDb(jobDetail);
            }
        }
    }


    // Update Build Job
    private CustomJob procUpdateBuildJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);
        long jobId = customJob.getId();

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(jobId);

        String originalRepositoryAccountId = jobDetail.getRepositoryAccountId();
        String originalRepositoryAccountPassword = jobDetail.getRepositoryAccountPassword();
        String requestRepositoryAccountId = customJob.getRepositoryAccountId();

        // CHECK REPOSITORY ID
        if (!originalRepositoryAccountId.equals(requestRepositoryAccountId)) {
            // CREATE CREDENTIALS TO CI SERVER
            credentialsService.createCredentials(customJob);
        }

        // CHECK REPOSITORY ID AND REPOSITORY PASSWORD
        if (originalRepositoryAccountId.equals(requestRepositoryAccountId) && !originalRepositoryAccountPassword.equals(customJob.getRepositoryAccountPassword())) {
            // UPDATE CREDENTIALS :: CHANGE REPOSITORY ACCOUNT PASSWORD
            credentialsService.updateCredentials(customJob);
        }

        // UPDATE BUILD JOB TO CI SERVER
        commonService.procGetCiServer(customJob.getCiServerUrl()).updateJob(customJob.getJobGuid(), jobTemplateService.getBuildJobTemplate(customJob), true);

        // SET JOB NAME :: CHECK FROM DATABASE
        if (!jobDetail.getJobName().equals(customJob.getJobName())) {
            customJob.setJobName(procSetJobName(customJob.getPipelineId(), customJob.getJobName()));
        }

        // GET REPOSITORY COMMIT REVISION
        CustomJob repositoryInfoModel = repositoryService.getRepositoryInfo(String.valueOf(jobId));

        // SET PARAM :: UPDATE JOB DETAIL
        customJob.setRepositoryCommitRevision(repositoryInfoModel.getRepositoryCommitRevision());

        // UPDATE BUILD JOB TO DATABASE
        resultModel = procUpdateJobToDb(customJob);

        return resultModel;
    }


    // Create Test Job
    private CustomJob procCreateTestJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);
        String jobGuid = procSetJobGuid(JobType.TEST);
        customJob.setJobGuid(jobGuid);

        // GET JOB DETAIL FROM DATABASE
        CustomJob buildJobDetail = procGetJobDetail(customJob.getBuildJobId());

        customJob.setBuilderType(buildJobDetail.getBuilderType());
        customJob.setRepositoryType(buildJobDetail.getRepositoryType());
        customJob.setRepositoryUrl(buildJobDetail.getRepositoryUrl());
        customJob.setRepositoryId(buildJobDetail.getRepositoryId());
        customJob.setRepositoryAccountId(buildJobDetail.getRepositoryAccountId());
        customJob.setRepositoryAccountPassword(buildJobDetail.getRepositoryAccountPassword());
        customJob.setRepositoryBranch(buildJobDetail.getRepositoryBranch());
        customJob.setRepositoryCommitRevision(buildJobDetail.getRepositoryCommitRevision());

        // TODO :: CALL CREATE INSPECTION PROJECT
        // TODO :: RETURN INSPECTION PROJECT ID
//        CustomJob resultCreatedInspectionModel = new CustomJob();
//        String inspectionProjectId = resultCreatedInspectionModel.getInspectionProjectId();

        // TODO :: CALL GET INSPECTION INFO
//        CustomJob resultDetailInspectionModel = new CustomJob();
        // TODO :: RETURN INSPECTION PROJECT INFO
        // TODO :: SET INSPECTION PROJECT NAME, INSPECTION PROJECT KEY
//        String inspectionProjectName = resultDetailInspectionModel.getInspectionProjectName();
//        String inspectionProjectKey = resultDetailInspectionModel.getInspectionProjectKey();

        // TEMP
        String inspectionProjectName = "rex-test";
        String inspectionProjectKey = "rex-test-key";

        customJob.setInspectionProjectName(inspectionProjectName);
        customJob.setInspectionProjectKey(inspectionProjectKey);


        // CREATE TEST JOB TO CI SERVER
        commonService.procGetCiServer(customJob.getCiServerUrl()).createJob(jobGuid, jobTemplateService.getTestJobTemplate(customJob), true);

        // SET JOB NAME :: CHECK FROM DATABASE
        customJob.setJobName(procSetJobName(customJob.getPipelineId(), customJob.getJobName()));

        // SET REPOSITORY ACCOUNT PASSWORD BY AES256
        customJob.setRepositoryAccountPassword(commonService.setPasswordByAES256(Constants.AES256Type.ENCODE, buildJobDetail.getRepositoryAccountPassword()));

        // INSERT TEST JOB TO DATABASE
        resultModel = procCreateJobToDb(customJob);

        // GET REPOSITORY COMMIT REVISION
        CustomJob repositoryInfoModel = repositoryService.getRepositoryInfo(String.valueOf(resultModel.getId()));

        // SET PARAM :: UPDATE JOB DETAIL
        resultModel.setRepositoryCommitRevision(repositoryInfoModel.getRepositoryCommitRevision());
        resultModel.setRepositoryAccountPassword(customJob.getRepositoryAccountPassword());

        // UPDATE TEST JOB TO DATABASE
        procUpdateJobToDb(resultModel);

        resultModel.setJobGuid(jobGuid);
        return resultModel;
    }


    // Update Test Job
    private CustomJob procUpdateTestJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        long jobId = customJob.getId();
        String reqJobName = customJob.getJobName();

        // TODO :: CALL CREATE INSPECTION PROJECT
        // TODO :: RETURN INSPECTION PROJECT ID
//        CustomJob resultCreatedInspectionModel = new CustomJob();
//        String inspectionProjectId = resultCreatedInspectionModel.getInspectionProjectId();

        // TODO :: CALL GET INSPECTION INFO
//        CustomJob resultDetailInspectionModel = new CustomJob();
        // TODO :: RETURN INSPECTION PROJECT INFO
        // TODO :: SET INSPECTION PROJECT NAME, INSPECTION PROJECT KEY
//        String inspectionProjectName = resultDetailInspectionModel.getInspectionProjectName();
//        String inspectionProjectKey = resultDetailInspectionModel.getInspectionProjectKey();

        // TEMP
        String inspectionProjectName = "rex-test";
        String inspectionProjectKey = "rex-test-key";

        customJob.setInspectionProjectName(inspectionProjectName);
        customJob.setInspectionProjectKey(inspectionProjectKey);


        // UPDATE TEST JOB TO CI SERVER
        commonService.procGetCiServer(customJob.getCiServerUrl()).updateJob(customJob.getJobGuid(), jobTemplateService.getTestJobTemplate(customJob), true);

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(jobId);

        // SET JOB NAME :: CHECK FROM DATABASE
        if (!jobDetail.getJobName().equals(reqJobName)) {
            customJob.setJobName(procSetJobName(customJob.getPipelineId(), reqJobName));
        }

        // GET REPOSITORY COMMIT REVISION
        CustomJob repositoryInfoModel = repositoryService.getRepositoryInfo(String.valueOf(jobId));

        // SET PARAM :: UPDATE JOB DETAIL
        customJob.setRepositoryCommitRevision(repositoryInfoModel.getRepositoryCommitRevision());

        // SET REPOSITORY ACCOUNT PASSWORD BY AES256
        customJob.setRepositoryAccountPassword(commonService.setPasswordByAES256(Constants.AES256Type.ENCODE, customJob.getRepositoryAccountPassword()));

        // UPDATE TEST JOB TO DATABASE
        resultModel = procUpdateJobToDb(customJob);

        return resultModel;
    }


    // Create Deploy Job
    private CustomJob procCreateDeployJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        String jobGuid = procSetJobGuid(JobType.DEPLOY);
        customJob.setJobGuid(jobGuid);

        // CREATE DEPLOY JOB TO CI SERVER
        commonService.procGetCiServer(customJob.getCiServerUrl()).createJob(jobGuid, jobTemplateService.getDeployJobTemplate(customJob), true);

        // SET JOB NAME :: CHECK FROM DATABASE
        customJob.setJobName(procSetJobName(customJob.getPipelineId(), customJob.getJobName()));

        // SET APP URL :: CF INFO DETAIL FROM DATABASE
        customJob.setAppUrl(procSetAppUrl(customJob));

        // INSERT DEPLOY JOB TO DATABASE
        resultModel = procCreateJobToDb(customJob);
        resultModel.setJobGuid(jobGuid);

        return resultModel;
    }


    // Set App Url
    private String procSetAppUrl(CustomJob customJob) {
        // GET CF INFO DETAIL
        return URL_PROTOCOL_STRING + customJob.getAppName() + cfInfoService.getCfInfo(customJob).getCfApiUrl().split(CF_API_URL_SPLIT_STRING)[1];
    }


    // Update Deploy Job
    private CustomJob procUpdateDeployJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        String reqJobName = customJob.getJobName();

        // UPDATE DEPLOY JOB TO CI SERVER
        commonService.procGetCiServer(customJob.getCiServerUrl()).updateJob(customJob.getJobGuid(), jobTemplateService.getDeployJobTemplate(customJob), true);

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(customJob.getId());

        // SET JOB NAME :: CHECK FROM DATABASE
        if (!jobDetail.getJobName().equals(reqJobName)) {
            customJob.setJobName(procSetJobName(customJob.getPipelineId(), reqJobName));
        }

        // SET APP URL :: CF INFO DETAIL FROM DATABASE
        customJob.setAppUrl(procSetAppUrl(customJob));

        // UPDATE DEPLOY JOB TO DATABASE
        resultModel = procUpdateJobToDb(customJob);

        return resultModel;
    }


    /**
     * Delete job custom job.
     *
     * @param id the id
     * @return the custom job
     * @throws IOException the io exception
     */
    CustomJob deleteJob(String id) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(Long.valueOf(id));

        // GET SERVICE INSTANCES DETAIL FROM DATABASE
        // SET CI SERVER URL
        jobDetail.setCiServerUrl(procGetServiceInstances(jobDetail.getServiceInstancesId()).getCiServerUrl());

        // DELETE JOB IN CI SERVER
        commonService.procGetCiServer(jobDetail.getCiServerUrl()).deleteJob(jobDetail.getJobGuid(), true);

        // DELETE WORKSPACE IN CI SERVER
        jobBuiltFileService.deleteWorkspace(jobDetail);

        // DELETE JOB TO DATABASE
        restTemplateService.send(Constants.TARGET_COMMON_API, REQ_URL + "/" + id, HttpMethod.DELETE, null, String.class);

        // DELETE BUILT FILE OF JOB
        // GET JOB HISTORY LIST FROM DATABASE
        List jobHistoryList = restTemplateService.send(Constants.TARGET_COMMON_API, REQ_URL + "/" + id + "/histories", HttpMethod.GET, null, List.class);
        int listSize = jobHistoryList.size();

        if (listSize > 0) {
            for (Object aJobHistoryList : jobHistoryList) {
                Map<String, Object> map = (Map<String, Object>) aJobHistoryList;
                int fileId = (int) map.get("fileId");

                if (String.valueOf(JobType.BUILD).equals(jobDetail.getJobType()) && (fileId > 0)) {
                    // GET FILE DETAIL FROM DATABASE
                    FileInfo fileInfo = restTemplateService.send(Constants.TARGET_COMMON_API, REQ_FILE_URL + "/" + fileId, HttpMethod.GET, null, FileInfo.class);

                    // DELETE BUILT FILE OF JOB IN BINARY STORAGE SERVER
                    restTemplateService.send(Constants.TARGET_BINARY_STORAGE_API, REQ_FILE_URL + "/fileDelete", HttpMethod.POST, fileInfo, String.class);

                    // DELETE BUILT FILE OF JOB
                    restTemplateService.send(Constants.TARGET_COMMON_API, REQ_FILE_URL + "/" + fileInfo.getId(), HttpMethod.DELETE, null, String.class);
                }
            }
        }

        // DELETE JOB HISTORY TO DATABASE
        restTemplateService.send(Constants.TARGET_COMMON_API, REQ_URL + "/" + id + REQ_HISTORY_URL, HttpMethod.DELETE, null, String.class);

        // SET JOB ORDER :: SET TO DATABASE
        procSetJobOrder(jobDetail, OperationType.DECREASE);

        return resultModel;
    }


    /**
     * Trigger job custom job.
     *
     * @param customJob  the custom job
     * @param jobHistory the job history
     * @return the custom job
     */
    CustomJob triggerJob(CustomJob customJob, JobHistory jobHistory) {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_FAIL);

        String schedulerModifiedPushYn = customJob.getSchedulerModifiedPushYn();

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(customJob.getId());
        jobDetail.setJobHistoryId(customJob.getJobHistoryId());

        // CHECK MODIFIED PUSH CALLED FROM SCHEDULER
        if (null != schedulerModifiedPushYn && Constants.USE_YN_Y.equals(schedulerModifiedPushYn)) {
            jobDetail.setTriggerType(String.valueOf(JobTriggerType.MODIFIED_PUSH));
        }

        // PROCESS TRIGGER JOB
        resultModel = procTriggerJob(jobDetail, jobHistory);

        return resultModel;
    }


    // Trigger Job
    private CustomJob procTriggerJob(CustomJob customJob, JobHistory jobHistory) {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_FAIL);

        customJob.setJobNumber(customJob.getJobNumber() + 1);
        String reqJobType = customJob.getJobType();

        // GET SERVICE INSTANCES DETAIL FROM DATABASE
        // SET CI SERVER URL
        customJob.setCiServerUrl(procGetServiceInstances(customJob.getServiceInstancesId()).getCiServerUrl());

        // BUILD JOB
        if (String.valueOf(JobType.BUILD).equals(reqJobType)) {
            resultModel = procTriggerBuildJob(customJob, jobHistory);
        }

        // TEST JOB
        if (String.valueOf(JobType.TEST).equals(reqJobType)) {
            resultModel = procTriggerTestJob(customJob, jobHistory);
        }

        // DEPLOY JOB
        if (String.valueOf(JobType.DEPLOY).equals(reqJobType)) {
            resultModel = procTriggerDeployJob(customJob, jobHistory);
        }

        resultModel.setId(customJob.getId());
        return resultModel;
    }


    /**
     * Trigger post job.
     *
     * @param customJob the custom job
     */
    @Async
    public void triggerPostJob(CustomJob customJob) {
        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(customJob.getId());

        // CHECK POST ACTION AND CHECK LAST JOB STATUS
        // "Y" = STOP IF JOB FAIL
        // "N" = CONTINUE IF JOB FAIL
        if ((Constants.USE_YN_Y.equals(jobDetail.getPostActionYn()) && !Constants.RESULT_STATUS_SUCCESS.equals(jobDetail.getLastJobStatus()))
                || !Constants.RESULT_STATUS_SUCCESS.equals(jobDetail.getLastJobStatus())) {
            return;
        }

        long currentJobId = jobDetail.getId();
        int currentGroupOrder = jobDetail.getGroupOrder();
        int currentJobOrder = jobDetail.getJobOrder();
        int previousJobNumber = 0;

        // GET JOB LIST FROM DATABASE
        // LOOP
        for (Object aResultList : procGetJobList(jobDetail.getPipelineId())) {
            Map<String, Object> map = (Map<String, Object>) aResultList;
            int tempJobId = (int) map.get("id");
            long nextJobId = (long) tempJobId;
            int nextGroupOrder = (int) map.get(GROUP_ORDER_STRING);
            int nextJobOrder = (int) map.get(JOB_ORDER_STRING);
            String nextJobTrigger = (String) map.get("jobTrigger");
            String nextJobType = (String) map.get("jobType");
            String nextDeployType = (String) map.get("deployType");
            String nextJobNumberCount = (String) map.get("previousJobNumberCount");

            // CHECK STATUS
            if ((currentJobId != nextJobId)
                    && (currentGroupOrder == nextGroupOrder)
                    && (currentJobOrder < nextJobOrder)
                    && procCheckTriggerPostJobStatus(nextJobTrigger, nextJobType, nextDeployType)) {

                // GET JOB DETAIL FROM DATABASE
                CustomJob nextJobDetail = procGetJobDetail(nextJobId);
                nextJobDetail.setTriggerType(String.valueOf(JobTriggerType.PREVIOUS_JOB_SUCCESS));
                nextJobDetail.setPreviousJobNumber(previousJobNumber);

                // TRIGGER JOB AND CHECK RESULT STATUS
                if (Constants.RESULT_STATUS_SUCCESS.equals(procTriggerJob(nextJobDetail, new JobHistory()).getResultStatus())) {
                    // TRIGGER POST JOB
                    triggerPostJob(nextJobDetail);
                    break;
                }
            }

            previousJobNumber = Integer.parseInt(nextJobNumberCount);
        }
    }


    // Check Trigger Post Job Status
    private boolean procCheckTriggerPostJobStatus(String jobTrigger, String jobType, String deployType) {
        boolean result = false;

        if ((null != jobTrigger) && (String.valueOf(JobTriggerType.PREVIOUS_JOB_SUCCESS).equals(jobTrigger))) {
            if ((null != jobType) && (String.valueOf(JobType.DEPLOY).equals(jobType))) {
                if ((null != deployType) && (!String.valueOf(JobConfig.DeployType.PRD).equals(deployType))) {
                    result = true;
                }
            } else {
                result = true;
            }
        }

        return result;
    }


    // Trigger Build Job
    private CustomJob procTriggerBuildJob(CustomJob customJob, JobHistory jobHistory) {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_FAIL);

        try {
            String resultStatus;
            int jobNumber;
            long jobDuration;
            long fileId = 0;
            long jobId = customJob.getId();

            // INSERT JOB HISTORY TO DATABASE
            JobHistory jobHistoryInsertResultModel = procInsertJobHistory(customJob, jobHistory);

            // TRIGGER BUILD JOB TO CI SERVER
            BuildWithDetails buildWithDetails = commonService.getCiTriggerHelper(customJob.getCiServerUrl()).triggerJobAndWaitUntilFinished(customJob.getJobGuid(), true);
            BuildResult buildResult = buildWithDetails.getResult();

            resultStatus = buildResult.name();
            jobNumber = buildWithDetails.getNumber();
            jobDuration = buildWithDetails.getDuration();

            if (Constants.RESULT_STATUS_SUCCESS.equals(resultStatus)) {
                // SET PARAM :: UPDATE BUILD JOB HISTORY TO DATABASE
                jobHistoryInsertResultModel.setJobNumber(jobNumber);
                jobHistoryInsertResultModel.setStatus(String.valueOf(JobTriggerStatusType.BUILT_FILE_UPLOADING));

                // UPDATE BUILD JOB HISTORY TO DATABASE
                procUpdateJobHistoryToDb(jobHistoryInsertResultModel);

                // DOWNLOAD BUILT FILE FROM PIPELINE SERVER
                // UPLOAD BUILT FILE WITH BINARY STORAGE API TO BINARY STORAGE SERVER
                fileId = jobBuiltFileService.setBuiltFile(customJob).getFileId();
                resultModel.setFileId(fileId);
            }

            // SET PARAM :: UPDATE BUILD JOB HISTORY TO DATABASE
            jobHistoryInsertResultModel.setJobNumber(jobNumber);
            jobHistoryInsertResultModel.setStatus(resultStatus);
            jobHistoryInsertResultModel.setDuration(jobDuration);
            jobHistoryInsertResultModel.setFileId(fileId);

            // UPDATE BUILD JOB HISTORY TO DATABASE
            procUpdateJobHistoryToDb(jobHistoryInsertResultModel);

            // GET REPOSITORY COMMIT REVISION
            CustomJob repositoryInfoModel = repositoryService.getRepositoryInfo(String.valueOf(jobId));

            // SET PARAM :: UPDATE JOB DETAIL
            customJob.setRepositoryCommitRevision(repositoryInfoModel.getRepositoryCommitRevision());

            // UPDATE BUILD JOB TO DATABASE
            procUpdateJobToDb(customJob);

            resultModel.setJobNumber(jobNumber);
            resultModel.setDuration(jobDuration);
            resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        } catch (IOException e) {
            throw new TriggerException("IOException :: {}", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TriggerException("InterruptedException :: {}", e);
        } catch (Exception e) {
            LOGGER.error("Exception :: {}", e);
        }

        return resultModel;
    }


    // Insert Job History
    private JobHistory procInsertJobHistory(CustomJob customJob, JobHistory jobHistory) {
        String triggerType = customJob.getTriggerType();
        int previousJobNumber = customJob.getPreviousJobNumber();
        int jobOrder = customJob.getJobOrder();
        int previousJobOrder = jobOrder - 1;

        // SET PARAM :: INSERT BUILD JOB HISTORY TO DATABASE
        jobHistory.setUserId(customJob.getUserId());
        jobHistory.setJobId(customJob.getId());
        jobHistory.setPreviousJobNumber(previousJobNumber);
        jobHistory.setJobNumber(0);
        jobHistory.setStatus(String.valueOf(JobTriggerStatusType.JOB_WORKING));
        jobHistory.setTriggerType((null != triggerType) ? triggerType : String.valueOf(JobTriggerType.MANUAL_TRIGGER));

        // CHECK PREVIOUS JOB NUMBER
        if (String.valueOf(JobTriggerType.MANUAL_TRIGGER).equals(jobHistory.getTriggerType()) && 0 == previousJobNumber && 1 < jobOrder) {

            // GET JOB LIST FROM DATABASE
            // LOOP
            for (Object aResultList : procGetJobList(customJob.getPipelineId())) {
                Map<String, Object> map = (Map<String, Object>) aResultList;
                if (previousJobOrder == (int) map.get(JOB_ORDER_STRING)) {
                    jobHistory.setPreviousJobNumber(Integer.parseInt((String) map.get("previousJobNumberCount")));
                }
            }
        }

        // CHECK ROLLBACK OF DEPLOY JOB
        if ((String.valueOf(JobConfig.JobType.DEPLOY).equals(customJob.getJobType())) && (0 < customJob.getJobHistoryId())) {
            jobHistory.setTriggerType(String.valueOf(JobTriggerType.ROLLBACK));
        }

        // INSERT BUILD JOB HISTORY TO DATABASE
        return procCreateJobHistoryToDb(jobHistory);
    }


    // Trigger Test Job
    private CustomJob procTriggerTestJob(CustomJob customJob, JobHistory jobHistory) {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_FAIL);

        try {
            String resultStatus;
            int jobNumber;
            long jobDuration;

            // INSERT JOB HISTORY TO DATABASE
            JobHistory jobHistoryInsertResultModel = procInsertJobHistory(customJob, jobHistory);

            // TRIGGER TEST JOB TO CI SERVER
            BuildWithDetails buildWithDetails = commonService.getCiTriggerHelper(customJob.getCiServerUrl()).triggerJobAndWaitUntilFinished(customJob.getJobGuid(), true);
            BuildResult buildResult = buildWithDetails.getResult();

            resultStatus = buildResult.name();
            jobNumber = buildWithDetails.getNumber();
            jobDuration = buildWithDetails.getDuration();

            // SET PARAM :: UPDATE TEST JOB HISTORY TO DATABASE
            jobHistoryInsertResultModel.setJobNumber(jobNumber);
            jobHistoryInsertResultModel.setStatus(resultStatus);
            jobHistoryInsertResultModel.setDuration(jobDuration);

            // UPDATE TEST JOB HISTORY TO DATABASE
            procUpdateJobHistoryToDb(jobHistoryInsertResultModel);

            // GET REPOSITORY COMMIT REVISION
            CustomJob repositoryInfoModel = repositoryService.getRepositoryInfo(String.valueOf(customJob.getId()));

            // SET PARAM :: UPDATE JOB DETAIL
            customJob.setRepositoryCommitRevision(repositoryInfoModel.getRepositoryCommitRevision());

            // UPDATE BUILD JOB TO DATABASE
            procUpdateJobToDb(customJob);

            resultModel.setJobNumber(jobNumber);
            resultModel.setDuration(jobDuration);
            resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TriggerException("InterruptedException :: {}", e);
        } catch (Exception e) {
            LOGGER.error("Exception :: {}", e);
        }

        return resultModel;
    }


    // Trigger Deploy Job
    private CustomJob procTriggerDeployJob(CustomJob customJob, JobHistory jobHistory) {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_FAIL);

        String resultStatus;
        int jobNumber;
        Long buildJobId = customJob.getBuildJobId();
        Long jobHistoryId = customJob.getJobHistoryId();
        long jobDuration;
        String reqUrl;
        String deployType = String.valueOf(JobConfig.DeployType.DEV);
        String blueGreenDeployStatus = customJob.getBlueGreenDeployStatus();
        Long fileId;

        // GET BUILD JOB DETAIL FROM DATABASE
        CustomJob buildJobModel = procGetJobDetail(buildJobId);
        CustomJob deployJobModel;

        // INSERT JOB HISTORY TO DATABASE
        JobHistory jobHistoryInsertResultModel = procInsertJobHistory(customJob, jobHistory);

        // CHECK ROLL BACK
        if (jobHistoryId > 0) {
            // ROLL BACK DEPLOY
            String appName = customJob.getAppName();
            String deployTargetOrg = customJob.getDeployTargetOrg();
            String deployTargetSpace = customJob.getDeployTargetSpace();

            // SET USER INPUT
            deployJobModel = new CustomJob();
            deployJobModel.setAppName(appName);
            deployJobModel.setDeployTargetOrg(deployTargetOrg);
            deployJobModel.setDeployTargetSpace(deployTargetSpace);

            try {
                // GET JOB DETAIL FROM DATABASE
                CustomJob updateDeployJobModel = procGetJobDetail(customJob.getId());

                // SET PARAM :: UPDATE DEPLOY JOB
                updateDeployJobModel.setAppName(appName);
                updateDeployJobModel.setDeployTargetOrg(deployTargetOrg);
                updateDeployJobModel.setDeployTargetSpace(deployTargetSpace);
                updateDeployJobModel.setManifestUseYn(customJob.getManifestUseYn());
                updateDeployJobModel.setManifestScript(customJob.getManifestScript());

                // UPDATE DEPLOY JOB
                procUpdateDeployJob(updateDeployJobModel);
            } catch (IOException e) {
                throw new TriggerException("IOException", e);
            }

        } else {
            // COMMON DEPLOY
            // GET DEPLOY JOB DETAIL FROM DATABASE
            deployJobModel = procGetJobDetail(customJob.getId());
        }

        reqUrl = REQ_URL + "/" + buildJobId + REQ_HISTORY_URL + "/status/" + Constants.EMPTY_VALUE + "/first";

        // GET BUILT FILE INFO FROM JOB HISTORY
        // GET JOB HISTORY FROM DATABASE
        fileId = restTemplateService.send(Constants.TARGET_COMMON_API, reqUrl, HttpMethod.GET, null, JobHistory.class).getFileId();

        // CHECK EXIST BUILT FILE
        if (fileId < 1) {
            jobHistoryInsertResultModel.setJobNumber(0);
            jobHistoryInsertResultModel.setStatus(String.valueOf(JobTriggerStatusType.FAILURE));
            jobHistoryInsertResultModel.setDuration(0);
            jobHistoryInsertResultModel.setFileId(0);

            // UPDATE DEPLOY JOB HISTORY TO DATABASE
            procUpdateJobHistoryToDb(jobHistoryInsertResultModel);

            resultModel.setJobNumber(0);
            resultModel.setDuration(0);
            resultModel.setResultStatus(Constants.RESULT_STATUS_FAIL);

            LOGGER.error("### BUILT FILE NOT EXISTED ###");
            return resultModel;
        }

        // GET FILE DETAIL FROM DATABASE
        FileInfo fileInfo = restTemplateService.send(Constants.TARGET_COMMON_API, REQ_FILE_URL + "/" + fileId, HttpMethod.GET, null, FileInfo.class);

        // SET DEPLOY_TYPE
        if (String.valueOf(JobConfig.DeployType.PRD).equals(customJob.getDeployType())) {
            deployType = blueGreenDeployStatus;
        }

        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("APP_NAME", deployJobModel.getAppName());
        paramMap.put("ORG_NAME", deployJobModel.getDeployTargetOrg());
        paramMap.put("SPACE_NAME", deployJobModel.getDeployTargetSpace());
        paramMap.put("BUILD_FILE_PATH", fileInfo.getFileUrl());
        paramMap.put("BUILD_FILE_NAME", fileInfo.getOriginalFileName());
        paramMap.put("BUILD_PACK_NAME", procSetBuildPackName(buildJobModel));
        paramMap.put("DEPLOY_TYPE", deployType);

        try {
            // TRIGGER DEPLOY JOB TO CI SERVER
            BuildWithDetails buildWithDetails = commonService.getCiTriggerHelper(customJob.getCiServerUrl()).triggerJobAndWaitUntilFinished(customJob.getJobGuid(), paramMap, true);
            BuildResult buildResult = buildWithDetails.getResult();

            resultStatus = buildResult.name();
            jobNumber = buildWithDetails.getNumber();
            jobDuration = buildWithDetails.getDuration();

            if (Constants.RESULT_STATUS_SUCCESS.equals(resultStatus) && !String.valueOf(JobConfig.DeployType.DEV).equals(deployType)) {
                // SET BLUE GREEN DEPLOY STATUS
                procSetBlueGreenDeployStatus(customJob, blueGreenDeployStatus);
            }

            // SET PARAM :: UPDATE DEPLOY JOB HISTORY TO DATABASE
            jobHistoryInsertResultModel.setJobNumber(jobNumber);
            jobHistoryInsertResultModel.setStatus(resultStatus);
            jobHistoryInsertResultModel.setDuration(jobDuration);
            jobHistoryInsertResultModel.setFileId(fileId);

            // UPDATE DEPLOY JOB HISTORY TO DATABASE
            procUpdateJobHistoryToDb(jobHistoryInsertResultModel);

            resultModel.setJobNumber(jobNumber);
            resultModel.setDuration(jobDuration);
            resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        } catch (IOException e) {
            throw new TriggerException("IOException", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TriggerException("InterruptedException", e);
        }

        return resultModel;
    }


    // Set Build Pack Name
    private String procSetBuildPackName(CustomJob customJob) {
        // JAVA :: DEFAULT
        String result = BuilderLanguage.JAVA.buildPackName;

        // TODO :: GO
        if (String.valueOf(BuilderLanguage.GO).equals(customJob.getBuilderType())) {
            result = BuilderLanguage.GO.buildPackName;
        }

        return result;
    }


    // Set Blue Green Deploy Status
    private void procSetBlueGreenDeployStatus(CustomJob customJob, String blueGreenDeployStatus) {
        // SET PARAM :: UPDATE DEPLOY JOB TO DATABASE
        String enumGreenDeploy = String.valueOf(JobConfig.BlueGreenDeployStatus.GREEN_DEPLOY);
        customJob.setBlueGreenDeployStatus(enumGreenDeploy.equals(blueGreenDeployStatus)
                ? String.valueOf(JobConfig.BlueGreenDeployStatus.BLUE_DEPLOY) : enumGreenDeploy);

        // UPDATE DEPLOY JOB TO DATABASE
        procUpdateJobToDb(customJob);
    }


    /**
     * Stop job custom job.
     *
     * @param customJob the custom job
     * @return the custom job
     * @throws IOException the io exception
     */
    CustomJob stopJob(CustomJob customJob) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        // GET SERVICE INSTANCES DETAIL FROM DATABASE
        // SET CI SERVER URL
        customJob.setCiServerUrl(procGetServiceInstances(customJob.getServiceInstancesId()).getCiServerUrl());

        // GET CI SERVER
        JenkinsServer ciServer = commonService.procGetCiServer(customJob.getCiServerUrl());

        // CANCEL QUEUE ITEM
        procCancelQueueItem(ciServer, customJob);

        // STOP TRIGGER JOB
        if (Constants.RESULT_STATUS_FAIL.equals(customJob.getResultStatus())) {
            procStopTriggerJob(ciServer, customJob);
        }

        return resultModel;
    }


    // Cancel Queue Item
    private void procCancelQueueItem(JenkinsServer ciServer, CustomJob customJob) throws IOException {
        // GET QUEUE ITEM LIST FROM CI SERVER
        List<QueueItem> queueItemList = ciServer.getQueue().getItems();
        Long queueItemId;

        if (!queueItemList.isEmpty()) {
            // GET JOB DETAIL FROM CI SERVER
            // GET QUEUE ITEM FROM CI SERVER
            // GET QUEUE ITEM ID FROM CI SERVER
            queueItemId = ciServer.getJob(customJob.getJobGuid()).getQueueItem().getId();

            for (QueueItem aQueueItemList : queueItemList) {
                if (Objects.equals(aQueueItemList.getId(), queueItemId)) {
                    // CANCEL QUEUE ITEM TO CI SERVER
                    commonService.procGetCiHttpClient(customJob.getCiServerUrl()).post("/queue/cancelItem?id=" + queueItemId, true);
                    customJob.setResultStatus(Constants.RESULT_STATUS_SUCCESS);
                }
            }
        } else {
            customJob.setResultStatus(Constants.RESULT_STATUS_FAIL);
        }
    }


    // Stop Trigger Job
    private void procStopTriggerJob(JenkinsServer ciServer, CustomJob customJob) throws IOException {
        // GET JOB DETAIL FROM CI SERVER
        if (!ciServer.getJob(customJob.getJobGuid()).getBuilds().isEmpty()) {
            // STOP TRIGGER JOB TO CI SERVER
            commonService.procGetCiHttpClient(customJob.getCiServerUrl()).post("/job/" + customJob.getJobGuid() + "/" + customJob.getJobNumber() + "/stop", true);
        }
    }


    /**
     * Gets job log.
     *
     * @param id        the id
     * @param jobNumber the job number
     * @return the job log
     * @throws IOException the io exception
     */
    CustomJob getJobLog(int id, int jobNumber) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail((long) id);
        String jobGuid = jobDetail.getJobGuid();

        // GET SERVICE INSTANCES DETAIL FROM DATABASE
        // SET CI SERVER URL
        jobDetail.setCiServerUrl(procGetServiceInstances(jobDetail.getServiceInstancesId()).getCiServerUrl());

        try {
            // GET JOB LOG FROM CI SERVER
            BuildWithDetails buildWithDetails = commonService.procGetCiServer(jobDetail.getCiServerUrl()).getJob(jobGuid).getBuildByNumber(jobNumber).details();

            resultModel.setConsoleOutputHtml(buildWithDetails.getConsoleOutputHtml());
            resultModel.setDuration(buildWithDetails.getDuration());
            resultModel.setEstimatedDuration(buildWithDetails.getEstimatedDuration());
            resultModel.setTimeStamp(String.valueOf(buildWithDetails.getTimestamp()));
            resultModel.setIsBuilding(getJobStatus(id, jobNumber).getIsBuilding());
        } catch (NullPointerException e) {
            LOGGER.error("NullPointerException :: {}", e);
        } catch (Exception e) {
            LOGGER.error("Exception :: {}", e);
        } finally {
            resultModel.setJobGuid(jobGuid);
            resultModel.setJobNumber(jobNumber);
            resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);
        }

        return resultModel;
    }


    /**
     * Gets job status.
     *
     * @param id        the id
     * @param jobNumber the job number
     * @return the job status
     * @throws IOException the io exception
     */
    CustomJob getJobStatus(int id, int jobNumber) throws IOException {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);
        resultModel.setIsBuilding(String.valueOf(false));

        boolean isBuilding = false;

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail((long) id);
        String jobGuid = jobDetail.getJobGuid();

        // GET SERVICE INSTANCES DETAIL FROM DATABASE
        // SET CI SERVER URL
        jobDetail.setCiServerUrl(procGetServiceInstances(jobDetail.getServiceInstancesId()).getCiServerUrl());

        // GET JOB DETAIL FROM CI SERVER
        JobWithDetails jobWithDetails = commonService.procGetCiServer(jobDetail.getCiServerUrl()).getJob(jobGuid);

        if (jobWithDetails != null) {
            // GET BUILD FROM CI SERVER
            Build build = jobWithDetails.getBuildByNumber(jobNumber);

            if (build != null) {
                // GET BUILD DETAIL FROM CI SERVER
                isBuilding = build.details().isBuilding();
            }
        }

        int lastJobNumber = jobDetail.getLastJobNumber();
        String lastJobStatus = jobDetail.getLastJobStatus();

        if (!isBuilding && jobNumber == lastJobNumber && procCheckLastJobStatus(lastJobStatus)) {
            isBuilding = true;
        }

        resultModel.setId((long) id);
        resultModel.setJobGuid(jobGuid);
        resultModel.setJobNumber(jobNumber);
        resultModel.setIsBuilding(String.valueOf(isBuilding));
        resultModel.setLastJobStatus(lastJobStatus);

        return resultModel;
    }


    // Check Last Job Status
    private boolean procCheckLastJobStatus(String lastJobStatus) {
        return String.valueOf(JobTriggerStatusType.JOB_WORKING).equals(lastJobStatus)
                || String.valueOf(JobTriggerStatusType.BUILT_FILE_UPLOADING).equals(lastJobStatus);
    }


    /**
     * Replicate job custom job.
     *
     * @param customJob the custom job
     * @return the custom job
     * @throws IOException the io exception
     */
    CustomJob replicateJob(CustomJob customJob) throws IOException {
        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(customJob.getId());

        // SET PARAM
        jobDetail.setId(null);
        jobDetail.setUserId(customJob.getUserId());
        jobDetail.setJobName(jobDetail.getJobName() + "-COPY");
        jobDetail.setJobOrder(jobDetail.getJobOrder() + 1);

        return createJob(jobDetail);
    }


    /**
     * Rearrange job order custom job.
     *
     * @param customJob the custom job
     * @return the custom job
     */
    CustomJob rearrangeJobOrder(CustomJob customJob) {
        CustomJob resultModel = new CustomJob();
        resultModel.setResultStatus(Constants.RESULT_STATUS_SUCCESS);

        long requestJobId = customJob.getId();

        // GET JOB DETAIL FROM DATABASE
        CustomJob jobDetail = procGetJobDetail(requestJobId);

        int jobDetailGroupOrder = jobDetail.getGroupOrder();
        int jobDetailJobOrder = jobDetail.getJobOrder();
        int requestJobOrder = customJob.getJobOrder();
        int updateJobOrder;
        boolean operationIncrease = requestJobOrder > jobDetailJobOrder;

        // GET JOB LIST FROM DATABASE
        for (Object aResultList : procGetJobList(jobDetail.getPipelineId())) {
            Map<String, Object> map = (Map<String, Object>) aResultList;
            int tempJobId = (int) map.get("id");
            long currentJobId = (long) tempJobId;
            int currentGroupOrder = (int) map.get(GROUP_ORDER_STRING);
            int currentJobOrder = (int) map.get(JOB_ORDER_STRING);
            updateJobOrder = requestJobOrder;

            // GET JOB DETAIL FROM DATABASE
            CustomJob updateJobDetail = procGetJobDetail(currentJobId);

            // CHECK
            if (jobDetailGroupOrder == currentGroupOrder) {
                if (requestJobId != currentJobId && operationIncrease) {
                    updateJobOrder = (requestJobOrder >= currentJobOrder && jobDetailJobOrder < currentJobOrder) ? currentJobOrder - 1 : currentJobOrder;
                }

                if (requestJobId != currentJobId && !operationIncrease) {
                    updateJobOrder = (requestJobOrder <= currentJobOrder && jobDetailJobOrder > currentJobOrder) ? currentJobOrder + 1 : currentJobOrder;
                }

                // SET PARAM :: UPDATE JOB TO DATABASE
                updateJobDetail.setJobOrder(updateJobOrder);

                // UPDATE JOB TO DATABASE
                procUpdateJobToDb(updateJobDetail);
            }
        }

        return resultModel;
    }


    /**
     * The enum Builder language.
     */
    enum BuilderLanguage {
        /**
         * Java builder language.
         */
        JAVA("java_buildpack_offline"),
        /**
         * Go builder language.
         */
        GO("go_buildpack");

        private String buildPackName;

        BuilderLanguage(String buildPackName) {
            this.buildPackName = buildPackName;
        }
    }


    /**
     * The enum Job type.
     */
    enum JobType {
        /**
         * Build job type.
         */
        BUILD,
        /**
         * Test job type.
         */
        TEST,
        /**
         * Deploy job type.
         */
        DEPLOY;
    }


    /**
     * The enum Operation type.
     */
    enum OperationType {
        /**
         * Increase operation type.
         */
        INCREASE,
        /**
         * Decrease operation type.
         */
        DECREASE;
    }


    /**
     * The enum Job trigger type.
     */
    enum JobTriggerType {
        /**
         * Previous job success job trigger type.
         */
        PREVIOUS_JOB_SUCCESS,
        /**
         * Modified push job trigger type.
         */
        MODIFIED_PUSH,
        /**
         * Manual trigger job trigger type.
         */
        MANUAL_TRIGGER,
        /**
         * Rollback job trigger type.
         */
        ROLLBACK;
    }


    /**
     * The enum Job trigger status type.
     */
    public enum JobTriggerStatusType {
        /**
         * Job working job trigger status type.
         */
        JOB_WORKING,
        /**
         * Built file uploading job trigger status type.
         */
        BUILT_FILE_UPLOADING,
        /**
         * Failure job trigger status type.
         */
        FAILURE;
    }

}
