package io.mosip.compliance.toolkit.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import io.mosip.compliance.toolkit.entity.MasterTemplatesEntity;
import io.mosip.compliance.toolkit.repository.MasterTemplatesRepository;
import io.mosip.compliance.toolkit.util.CommonUtil;
import io.mosip.compliance.toolkit.util.RandomIdGenerator;
import io.mosip.kernel.core.authmanager.authadapter.model.AuthUserDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.mosip.commons.khazana.spi.ObjectStoreAdapter;
import io.mosip.compliance.toolkit.config.LoggerConfiguration;
import io.mosip.compliance.toolkit.constants.AppConstants;
import io.mosip.compliance.toolkit.constants.SdkPurpose;
import io.mosip.compliance.toolkit.constants.ToolkitErrorCodes;
import io.mosip.compliance.toolkit.exceptions.ToolkitException;
import io.mosip.kernel.core.http.ResponseWrapper;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.virusscanner.exception.VirusScannerException;
import io.mosip.kernel.core.virusscanner.spi.VirusScanner;

@Service
public class ResourceManagementService {

    private static final String BLANK_SPACE = " ";

    private static final String ZIP_EXT = ".zip";

    private static final String VM_EXT = ".vm";

    private static final String DEFAULT_TEMPLATE_VERSION = "v1";

    private static final String JSON_EXT = ".json";

    private static final String UNDERSCORE = "_";

    private static final String SBI_SCHEMA = AppConstants.SCHEMAS + UNDERSCORE + AppConstants.SBI;

    private static final String SDK_SCHEMA = AppConstants.SCHEMAS + UNDERSCORE + AppConstants.SDK;

    private static final String ABIS_SCHEMA = AppConstants.SCHEMAS + UNDERSCORE + AppConstants.ABIS;

    @Value("${mosip.toolkit.document.scan}")
    private Boolean scanDocument;

    /**
     * Autowired reference for {@link #VirusScanner}
     */
    @Autowired
    VirusScanner<Boolean, InputStream> virusScan;

    @Autowired
    MasterTemplatesRepository masterTemplatesRepository;

    @Value("$(mosip.toolkit.api.id.resource.file.post)")
    private String postResourceFileId;

    @Value("${mosip.kernel.objectstore.account-name}")
    private String objectStoreAccountName;

    @Qualifier("S3Adapter")
    @Autowired
    private ObjectStoreAdapter objectStore;

    private Logger log = LoggerConfiguration.logConfig(ResourceManagementService.class);

    private AuthUserDetails authUserDetails() {
        return (AuthUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private String getUserBy() {
        String crBy = authUserDetails().getMail();
        return crBy;
    }

    public ResponseWrapper<Boolean> uploadResourceFile(String type, String version, MultipartFile file) {
        ResponseWrapper<Boolean> responseWrapper = new ResponseWrapper<>();
        boolean status = false;
        try {
            performFileValidation(file);
            if (Objects.nonNull(type)) {
                if ((type.equals(SBI_SCHEMA) || type.equals(SDK_SCHEMA)) && !Objects.nonNull(version)) {
                    throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorCode(),
                            ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorMessage());
                }
                String fileName = file.getOriginalFilename();
                String container = null;
                String objectName = null;
                switch (type) {
                    case AppConstants.MOSIP_DEFAULT:
                        if (Objects.isNull(fileName) || !fileName.endsWith(ZIP_EXT)) {
                            throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorCode(),
                                    ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorMessage());
                        }
                        container = AppConstants.TESTDATA;
                        String purposeDefault = fileName.replace(AppConstants.MOSIP_DEFAULT + UNDERSCORE, "")
                                .replace(ZIP_EXT, "");
                        if (purposeDefault.contains(AppConstants.ABIS)) {
                            objectName = AppConstants.MOSIP_DEFAULT + UNDERSCORE + purposeDefault.toUpperCase() + ZIP_EXT;
                        } else {
                            SdkPurpose sdkPurposeDefault = SdkPurpose.valueOf(purposeDefault);
                            objectName = AppConstants.MOSIP_DEFAULT + UNDERSCORE + sdkPurposeDefault.toString().toUpperCase()
                                    + ZIP_EXT;
                        }
                        break;
                    case AppConstants.SCHEMAS:
                        if (Objects.isNull(fileName) || !fileName.equals(AppConstants.TESTCASE_SCHEMA_JSON)) {
                            throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorCode(),
                                    ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorMessage());
                        }
                        container = AppConstants.SCHEMAS.toLowerCase();
                        objectName = fileName;
                        break;
                    case SBI_SCHEMA:
                        if (Objects.isNull(fileName) || !fileName.endsWith(JSON_EXT)) {
                            throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorCode(),
                                    ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorMessage());
                        }
                        container = AppConstants.SCHEMAS.toLowerCase() + "/" + AppConstants.SBI.toLowerCase() + "/" + version;
                        objectName = fileName;
                        break;
                    case SDK_SCHEMA:
                        if (Objects.isNull(fileName) || !fileName.endsWith(JSON_EXT)) {
                            throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorCode(),
                                    ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorMessage());
                        }
                        container = AppConstants.SCHEMAS.toLowerCase() + "/" + AppConstants.SDK.toLowerCase() + "/" + version;
                        objectName = fileName;
                        break;
                    case ABIS_SCHEMA:
                        if (Objects.isNull(fileName) || !fileName.endsWith(JSON_EXT)) {
                            throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorCode(),
                                    ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorMessage());
                        }
                        container = AppConstants.SCHEMAS.toLowerCase() + "/" + AppConstants.ABIS.toLowerCase() + "/" + version;
                        objectName = fileName;
                        break;
                    default:
                        throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorCode(),
                                ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorMessage());
                }
                InputStream is = file.getInputStream();
                status = putInObjectStore(container, objectName, is);
                is.close();
            } else {
                String errorCode = ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorCode();
                String errorMessage = ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorMessage();
                responseWrapper.setErrors(CommonUtil.getServiceErr(errorCode, errorMessage));
            }
        } catch (ToolkitException ex) {
            log.debug("sessionId", "idType", "id", ex.getStackTrace());
            log.error("sessionId", "idType", "id",
                    "In uploadResourceFile method of ResourceManagementService Service - " + ex.getMessage());
            String errorCode = ex.getErrorCode();
            String errorMessage = ex.getMessage();
            responseWrapper.setErrors(CommonUtil.getServiceErr(errorCode, errorMessage));
        } catch (Exception ex) {
            log.debug("sessionId", "idType", "id", ex.getStackTrace());
            log.error("sessionId", "idType", "id",
                    "In uploadResourceFile method of ResourceManagementService Service - " + ex.getMessage());
            String errorCode = ToolkitErrorCodes.RESOURCE_UPLOAD_ERROR.getErrorCode();
            String errorMessage = ToolkitErrorCodes.RESOURCE_UPLOAD_ERROR.getErrorMessage() + BLANK_SPACE + ex.getMessage();
            responseWrapper.setErrors(CommonUtil.getServiceErr(errorCode, errorMessage));
        }
        responseWrapper.setId(postResourceFileId);
        responseWrapper.setResponse(status);
        responseWrapper.setVersion(AppConstants.VERSION);
        responseWrapper.setResponsetime(LocalDateTime.now());
        return responseWrapper;
    }

    private boolean putInObjectStore(String container, String objectName, InputStream data) {
        return objectStore.putObject(objectStoreAccountName, container, null, null, objectName, data);
    }

    private boolean isVirusScanSuccess(MultipartFile file) {
        try {
            log.info("sessionId", "idType", "id", "In isVirusScanSuccess method of ResourceManagementService");
            return virusScan.scanDocument(file.getBytes());
        } catch (Exception e) {
            log.debug("sessionId", "idType", "id", e.getStackTrace());
            log.error("sessionId", "idType", "id",
                    "In isVirusScanSuccess method of ResourceManagementService Service - " + e.getMessage());
            throw new VirusScannerException(ToolkitErrorCodes.RESOURCE_UPLOAD_ERROR.getErrorCode(),
                    ToolkitErrorCodes.RESOURCE_UPLOAD_ERROR.getErrorMessage() + e.getMessage());
        }
    }

    public ResponseWrapper<Boolean> uploadTemplate(String langCode, String templateName, MultipartFile file) {
        ResponseWrapper<Boolean> responseWrapper = new ResponseWrapper<>();
        boolean status = false;
        try {
            performFileValidation(file);
            String fileName = file.getOriginalFilename();
            if (Objects.nonNull(langCode) && Objects.nonNull(templateName)) {
                if (Objects.isNull(fileName) || !fileName.endsWith(VM_EXT)) {
                    throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorCode(),
                            ToolkitErrorCodes.INVALID_REQUEST_BODY.getErrorMessage());
                }
                MasterTemplatesEntity masterTemplatesEntity = new MasterTemplatesEntity();

                LocalDateTime nowDate = LocalDateTime.now(ZoneOffset.UTC);
                masterTemplatesEntity.setId(RandomIdGenerator.generateUUID("id", "", 36));
                masterTemplatesEntity.setLangCode(langCode);
                masterTemplatesEntity.setTemplateName(templateName);
                masterTemplatesEntity.setCrBy(this.getUserBy());
                masterTemplatesEntity.setCrDtimes(nowDate);

                InputStream inputStream = file.getInputStream();
                byte[] bytes = inputStream.readAllBytes();
                String template = new String(bytes, StandardCharsets.UTF_8);
                masterTemplatesEntity.setTemplate(template);

                log.info("sessionId", "idType", "id", "fetching previous template timestamp for langCode :", langCode
                        , "and template name", templateName);
                String previousTemplateVersion = masterTemplatesRepository.getPreviousTemplateVersion(langCode, templateName);
                if (Objects.nonNull(previousTemplateVersion)) {
                    String templateVersion = incrementTemplateVersion(previousTemplateVersion);
                    masterTemplatesEntity.setVersion(templateVersion);
                } else {
                    masterTemplatesEntity.setVersion(DEFAULT_TEMPLATE_VERSION);
                }

                masterTemplatesRepository.save(masterTemplatesEntity);
                log.info("sessionId", "idType", "id", "saving template in Db having language code :", langCode
                        , "and template name", templateName);
                status = true;

            } else {
                String errorCode = ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorCode();
                String errorMessage = ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorMessage();
                responseWrapper.setErrors(CommonUtil.getServiceErr(errorCode, errorMessage));
            }
        } catch (ToolkitException ex) {
            log.debug("sessionId", "idType", "id", ex.getStackTrace());
            log.error("sessionId", "idType", "id",
                    "In uploadTemplate method of ResourceManagementService Service - " + ex.getMessage());
            String errorCode = ex.getErrorCode();
            String errorMessage = ex.getMessage();
            responseWrapper.setErrors(CommonUtil.getServiceErr(errorCode, errorMessage));
        } catch (Exception ex) {
            log.debug("sessionId", "idType", "id", ex.getStackTrace());
            log.error("sessionId", "idType", "id",
                    "In uploadTemplate method of ResourceManagementService Service - " + ex.getMessage());
            String errorCode = ToolkitErrorCodes.RESOURCE_UPLOAD_ERROR.getErrorCode();
            String errorMessage = ToolkitErrorCodes.RESOURCE_UPLOAD_ERROR.getErrorMessage() + BLANK_SPACE + ex.getMessage();
            responseWrapper.setErrors(CommonUtil.getServiceErr(errorCode, errorMessage));
        }
        responseWrapper.setId(postResourceFileId);
        responseWrapper.setResponse(status);
        responseWrapper.setVersion(AppConstants.VERSION);
        responseWrapper.setResponsetime(LocalDateTime.now());
        return responseWrapper;
    }

    public void performFileValidation(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();

        // check if the file is null or empty
        if (Objects.isNull(file) || file.isEmpty() || file.getSize() <= 0) {
            throw new ToolkitException(ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorCode(),
                    ToolkitErrorCodes.INVALID_REQUEST_PARAM.getErrorMessage());
        }

        // Validate if the file has extensions
        if (!originalFilename.contains(".")) {
            throw new ToolkitException(ToolkitErrorCodes.FILE_WITHOUT_EXTENSIONS.getErrorCode(),
                    ToolkitErrorCodes.FILE_WITHOUT_EXTENSIONS.getErrorMessage());
        }

        // Validate if there are multiple extensions
        if (originalFilename.split("\\.").length > 2) {
            throw new ToolkitException(ToolkitErrorCodes.FILE_WITH_MULTIPLE_EXTENSIONS.getErrorCode(),
                    ToolkitErrorCodes.FILE_WITH_MULTIPLE_EXTENSIONS.getErrorMessage());
        }

        // Perform virus scanning if enabled
        if (scanDocument && !isVirusScanSuccess(file)) {
            throw new ToolkitException(ToolkitErrorCodes.VIRUS_FOUND.getErrorCode(),
                    ToolkitErrorCodes.VIRUS_FOUND.getErrorMessage());
        }
    }

    public String incrementTemplateVersion(String templateVersion) {
        if (!templateVersion.matches("v\\d+")) {
            throw new ToolkitException(ToolkitErrorCodes.TOOLKIT_TEMPLATE_INVALID_VERSION_FORMAT.getErrorCode(),
                    ToolkitErrorCodes.TOOLKIT_TEMPLATE_INVALID_VERSION_FORMAT.getErrorMessage());
        }
        // Extract numeric part of the version
        String versionNumber = templateVersion.substring(1);
        int version = Integer.parseInt(versionNumber);
        // Increment the version
        version++;
        // Construct the new version string
        return "v" + version;
    }

}
