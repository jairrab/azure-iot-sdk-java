/*
 *
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 *
 */

package com.microsoft.azure.sdk.iot.provisioning.device.internal.task;

import com.microsoft.azure.sdk.iot.provisioning.device.*;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.ProvisioningDeviceClientConfig;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.exceptions.ProvisioningDeviceConnectionException;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.parser.DeviceRegistrationResultParser;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.parser.RegistrationOperationStatusParser;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.exceptions.ProvisioningDeviceHubException;
import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProvider;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.contract.ProvisioningDeviceClientContract;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.exceptions.ProvisioningDeviceClientAuthenticationException;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.exceptions.ProvisioningDeviceClientException;
import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProviderTpm;
import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProviderX509;
import com.microsoft.azure.sdk.iot.provisioning.security.exceptions.SecurityProviderException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientStatus.*;
import static org.apache.commons.codec.binary.Base64.decodeBase64;

@Slf4j
public class ProvisioningTask implements Callable<Object>
{
    private static final int MAX_THREADS_TO_RUN = 2;
    private static final int MAX_TIME_TO_WAIT_FOR_REGISTRATION = 1000000;
    private static final int MAX_TIME_TO_WAIT_FOR_STATUS_UPDATE = 10000;
    private static final String THREAD_NAME = "azure-iot-sdk-ProvisioningTask";

    private final SecurityProvider securityProvider;
    private final ProvisioningDeviceClientContract provisioningDeviceClientContract;
    private final ProvisioningDeviceClientConfig provisioningDeviceClientConfig;

    private final ProvisioningDeviceClientRegistrationCallback provisioningDeviceClientRegistrationCallback;
    private final Object dpsRegistrationCallbackContext;

    private final Authorization authorization;
    @SuppressWarnings("unused") // Used in a number of methods to report state, may be used for expansion
    private ProvisioningDeviceClientStatus dpsStatus = null;

    private final ExecutorService executor;

    /**
     * Constructor for creating a provisioning task
     * @param provisioningDeviceClientConfig Config that contains details pertaining to Service
     * @param provisioningDeviceClientContract Contract with the service over the specified protocol
     * @throws ProvisioningDeviceClientException If any of the input parameters are invalid then this exception is thrown
     */
    public ProvisioningTask(ProvisioningDeviceClientConfig provisioningDeviceClientConfig,
                            ProvisioningDeviceClientContract provisioningDeviceClientContract) throws ProvisioningDeviceClientException
    {
        if (provisioningDeviceClientContract == null)
        {
            throw new ProvisioningDeviceClientException(new IllegalArgumentException("DPS Transport cannot be null"));
        }

        if (provisioningDeviceClientConfig == null)
        {
            throw new ProvisioningDeviceClientException(new IllegalArgumentException("Config cannot be null"));
        }

        //SRS_ProvisioningTask_25_001: [ Constructor shall save provisioningDeviceClientConfig, provisioningDeviceClientContract.]
        this.provisioningDeviceClientConfig = provisioningDeviceClientConfig;
        this.securityProvider = provisioningDeviceClientConfig.getSecurityProvider();

        if (securityProvider == null)
        {
            //SRS_ProvisioningTask_25_002: [ Constructor shall shall throw ProvisioningDeviceClientException if the securityProvider is null.]
            throw new ProvisioningDeviceClientException(new IllegalArgumentException("Security client cannot be null"));
        }

        this.provisioningDeviceClientContract = provisioningDeviceClientContract;
        this.provisioningDeviceClientRegistrationCallback = provisioningDeviceClientConfig.getRegistrationCallback();
        this.dpsRegistrationCallbackContext = provisioningDeviceClientConfig.getRegistrationCallbackContext();

        //SRS_ProvisioningTask_25_004: [ This method shall throw ProvisioningDeviceClientException if the provisioningDeviceClientRegistrationCallback is null. ]
        if (provisioningDeviceClientRegistrationCallback == null)
        {
            throw new ProvisioningDeviceClientException(new IllegalArgumentException("Registration callback cannot be null"));
        }

        this.authorization = new Authorization();
        //SRS_ProvisioningTask_25_015: [ Constructor shall start the executor with a fixed thread pool of size 2.]
        this.executor = Executors.newFixedThreadPool(MAX_THREADS_TO_RUN);
    }

    private void invokeRegistrationCallback(RegistrationResult registrationInfo, Exception e) throws ProvisioningDeviceClientException
    {
        if (this.provisioningDeviceClientRegistrationCallback != null)
        {
            this.provisioningDeviceClientRegistrationCallback.run(registrationInfo, e, this.dpsRegistrationCallbackContext);
        }
        else
        {
            throw new ProvisioningDeviceClientException(new IllegalArgumentException("Registration callback cannot be null"));
        }
    }

    private RegistrationOperationStatusParser invokeRegister() throws InterruptedException, ExecutionException, TimeoutException,
                                                                      ProvisioningDeviceClientException
    {
        RegisterTask registerTask = new RegisterTask(this.provisioningDeviceClientConfig, securityProvider,
                                                     provisioningDeviceClientContract, authorization);
        FutureTask<RegistrationOperationStatusParser> futureRegisterTask = new FutureTask<>(registerTask);
        executor.submit(futureRegisterTask);
        RegistrationOperationStatusParser registrationOperationStatusParser = futureRegisterTask.get(MAX_TIME_TO_WAIT_FOR_REGISTRATION,
                                                                                                      TimeUnit.MILLISECONDS);
       if (registrationOperationStatusParser == null)
        {
            this.dpsStatus = PROVISIONING_DEVICE_STATUS_ERROR;
            throw new ProvisioningDeviceClientAuthenticationException("Registration response could not be retrieved, " +
                    "authentication failure");
        }

        ProvisioningStatus status = ProvisioningStatus.fromString(registrationOperationStatusParser.getStatus());
        if (status == null)
        {
            this.dpsStatus = PROVISIONING_DEVICE_STATUS_ERROR;
            throw new ProvisioningDeviceClientAuthenticationException("Received null status for registration, " +
                    "authentication failure");
        }

        if (registrationOperationStatusParser.getOperationId() == null)
        {
            throw new ProvisioningDeviceClientAuthenticationException("operation id could not be retrieved, " +
                    "authentication failure");
        }

        return registrationOperationStatusParser;
    }

    private RegistrationOperationStatusParser invokeStatus(String operationId) throws TimeoutException, InterruptedException, ExecutionException,
                                                                                      ProvisioningDeviceClientException
    {
        Thread.sleep(provisioningDeviceClientContract.getRetryValue());
        StatusTask statusTask = new StatusTask(
                securityProvider,
                provisioningDeviceClientContract,
                provisioningDeviceClientConfig,
                operationId,
                this.authorization);

        FutureTask<RegistrationOperationStatusParser> futureStatusTask = new FutureTask<>(statusTask);
        executor.submit(futureStatusTask);
        RegistrationOperationStatusParser statusRegistrationOperationStatusParser =  futureStatusTask.get(MAX_TIME_TO_WAIT_FOR_STATUS_UPDATE, TimeUnit.MILLISECONDS);

        if (statusRegistrationOperationStatusParser == null)
        {
            this.dpsStatus = PROVISIONING_DEVICE_STATUS_ERROR;
            throw new ProvisioningDeviceClientAuthenticationException("Status response could not be retrieved, " +
                    "authentication failure");
        }
        if (statusRegistrationOperationStatusParser.getStatus() == null)
        {
            this.dpsStatus = PROVISIONING_DEVICE_STATUS_ERROR;
            throw new ProvisioningDeviceClientAuthenticationException("Status could not be retrieved, " +
                    "authentication failure");
        }

        if (ProvisioningStatus.fromString(statusRegistrationOperationStatusParser.getStatus()) == null)
        {
            this.dpsStatus = PROVISIONING_DEVICE_STATUS_ERROR;
            throw new ProvisioningDeviceClientAuthenticationException("Status could not be retrieved, " +
                    "authentication failure");
        }
        return statusRegistrationOperationStatusParser;
    }

    private void executeStateMachineForStatus(RegistrationOperationStatusParser registrationOperationStatusParser)
            throws TimeoutException, InterruptedException, ExecutionException, ProvisioningDeviceClientException, SecurityProviderException

    {
        boolean isContinue = false;
        ProvisioningStatus nextStatus = ProvisioningStatus.fromString(registrationOperationStatusParser.getStatus());
        log.info("Current provisioning status: {}", nextStatus);
        // continue invoking for status until a terminal state is reached
        do
        {
            if (nextStatus == null)
            {
                throw new ProvisioningDeviceClientException("Did not receive a valid status");
            }

            switch (nextStatus)
            {
                case UNASSIGNED:
                    //intended fall through
                case ASSIGNING:
                    log.trace("Polling device provisioning service for status of registration...");
                    registrationOperationStatusParser = this.invokeStatus(registrationOperationStatusParser.getOperationId());
                    nextStatus = ProvisioningStatus.fromString(registrationOperationStatusParser.getStatus());
                    isContinue = true;
                    break;
                case ASSIGNED:
                    this.dpsStatus = PROVISIONING_DEVICE_STATUS_ASSIGNED;
                    DeviceRegistrationResultParser registrationStatus = registrationOperationStatusParser.getRegistrationState();

                    if (registrationStatus == null
                            || registrationStatus.getAssignedHub() == null
                            || registrationStatus.getAssignedHub().isEmpty()
                            || registrationStatus.getDeviceId() == null
                            || registrationStatus.getDeviceId().isEmpty())
                    {
                        throw new ProvisioningDeviceClientException("Could not retrieve Assigned Hub or Device ID and status changed to Assigned");
                    }

                    RegistrationResult registrationInfo = new RegistrationResult(
                                                            registrationStatus.getAssignedHub(),
                                                            registrationStatus.getDeviceId(),
                                                            registrationStatus.getPayload(), PROVISIONING_DEVICE_STATUS_ASSIGNED);

                    registrationInfo.setRegistrationId(registrationStatus.getRegistrationId());
                    registrationInfo.setStatus(registrationStatus.getStatus());
                    registrationInfo.setSubstatus(ProvisioningDeviceClientSubstatus.fromString(registrationStatus.getSubstatus()));
                    registrationInfo.setCreatedDateTimeUtc(registrationStatus.getCreatedDateTimeUtc());
                    registrationInfo.setLastUpdatesDateTimeUtc(registrationStatus.getLastUpdatesDateTimeUtc());
                    registrationInfo.setETag(registrationStatus.getETag());

                    if (this.securityProvider instanceof SecurityProviderTpm)
                    {
                        if (registrationStatus.getTpm() == null
                                || registrationStatus.getTpm().getAuthenticationKey() == null
                                || registrationStatus.getTpm().getAuthenticationKey().isEmpty())
                        {
                            throw new ProvisioningDeviceClientException("Could not retrieve Authentication key when status was assigned");
                        }

                        String authenticationKey = registrationStatus.getTpm().getAuthenticationKey();
                        ((SecurityProviderTpm) this.securityProvider).activateIdentityKey(decodeBase64(authenticationKey.getBytes(StandardCharsets.UTF_8)));
                    }
                    log.info("Device provisioning service assigned the device successfully");
                    this.invokeRegistrationCallback(registrationInfo, null);
                    isContinue = false;
                    break;
                case FAILED:
                    this.dpsStatus = PROVISIONING_DEVICE_STATUS_FAILED;
                    String errorMessage = registrationOperationStatusParser.getRegistrationState().getErrorMessage();
                    ProvisioningDeviceHubException dpsHubException = new ProvisioningDeviceHubException(errorMessage);
                    if (registrationOperationStatusParser.getRegistrationState().getErrorCode() != null)
                    {
                        dpsHubException.setErrorCode(registrationOperationStatusParser.getRegistrationState().getErrorCode());
                    }

                    registrationInfo = new RegistrationResult(null, null, null, PROVISIONING_DEVICE_STATUS_FAILED);
                    log.error("Device provisioning service failed to provision the device, finished with status FAILED: {}", errorMessage);
                    this.invokeRegistrationCallback(registrationInfo, dpsHubException);
                    isContinue = false;
                    break;
                case DISABLED:
                    this.dpsStatus = PROVISIONING_DEVICE_STATUS_DISABLED;
                    String disabledErrorMessage = registrationOperationStatusParser.getRegistrationState().getErrorMessage();
                    dpsHubException = new ProvisioningDeviceHubException(disabledErrorMessage);
                    if (registrationOperationStatusParser.getRegistrationState().getErrorCode() != null)
                    {
                        dpsHubException.setErrorCode(registrationOperationStatusParser.getRegistrationState().getErrorCode());
                    }

                    registrationInfo = new RegistrationResult(null, null, null, PROVISIONING_DEVICE_STATUS_DISABLED);
                    log.error("Device provisioning service failed to provision the device, finished with status DISABLED: {}", disabledErrorMessage);
                    this.invokeRegistrationCallback(registrationInfo, dpsHubException);
                    isContinue = false;
                    break;
            }
        }
        while (isContinue);
    }

    // this thread will continue to run until DPS status is assigned and registered or exit on error
    // DPS State machine

    /**
     * This method executes the State machine with the device goes through during registration.
     * @return Returns {@code null}
     * @throws Exception This exception is thrown if any of the exception during execution is not handled.
     */
    @Override
    public Object call() throws Exception
    {
        // The thread doesn't have any opened connections associated to it yet.
        String threadName = this.provisioningDeviceClientContract.getHostName()
                + "-"
                + this.provisioningDeviceClientConfig.getUniqueIdentifier()
                + "-Cxn"
                + "PendingConnectionId"
                + "-"
                + THREAD_NAME;

        Thread.currentThread().setName(threadName);

        try
        {
            //SRS_ProvisioningTask_25_015: [ This method shall invoke open call on the contract.]
            log.info("Opening the connection to device provisioning service...");
            provisioningDeviceClientContract.open(new RequestData(securityProvider.getRegistrationId(), securityProvider.getSSLContext(), securityProvider instanceof SecurityProviderX509, provisioningDeviceClientConfig.getPayload()));
            //SRS_ProvisioningTask_25_007: [ This method shall invoke Register task and status task to execute the state machine of the service as per below rules.]
            /*
            Service State Machine Rules

            SRS_ProvisioningTask_25_008: [ This method shall invoke register task and wait for it to complete.]
            SRS_ProvisioningTask_25_009: [ This method shall invoke status callback with status PROVISIONING_DEVICE_STATUS_AUTHENTICATED if register task completes successfully.]
            SRS_ProvisioningTask_25_010: [ This method shall invoke status task to get the current state of the device registration and wait until a terminal state is reached.]
            SRS_ProvisioningTask_25_011: [ Upon reaching one of the terminal state i.e ASSIGNED, this method shall invoke registration callback with the information retrieved from service for IotHub Uri and DeviceId. Also if status callback is defined then it shall be invoked with status PROVISIONING_DEVICE_STATUS_ASSIGNED.]
            SRS_ProvisioningTask_25_012: [ Upon reaching one of the terminal states i.e FAILED or DISABLED, this method shall invoke registration callback with error message received from service. Also if status callback is defined then it shall be invoked with status PROVISIONING_DEVICE_STATUS_ERROR.]
            SRS_ProvisioningTask_25_013: [ Upon reaching intermediate state i.e UNASSIGNED or ASSIGNING, this method shall continue to query for status until a terminal state is reached. Also if status callback is defined then it shall be invoked with status PROVISIONING_DEVICE_STATUS_ASSIGNING.]
            State diagram :

            One of the following states can be reached from register or status task - (A) Unassigned (B) Assigning (C) Assigned (D) Fail (E) Disable

                Return-State	A	            B	        C	        D	        E
                Register-State	B, C, D, E	    C, D, E	    terminal	terminal	terminal
                Status-State	B, C, D, E	    C, D, E	    terminal	terminal	terminal
             */

            String connectionId = this.provisioningDeviceClientConfig.getUniqueIdentifier();
            if (connectionId == null) {
                // For Symetric Key authentication, connection is not open until the registration is invoked.
                connectionId = "PendingConnectionId";
            }

            threadName = this.provisioningDeviceClientContract.getHostName()
                    + "-"
                    + this.provisioningDeviceClientConfig.getUniqueIdentifier()
                    + "-Cxn"
                    + connectionId
                    + "-"
                    + THREAD_NAME;

            Thread.currentThread().setName(threadName);

            log.info("Connection to device provisioning service opened successfully, sending initial device registration message");
            RegistrationOperationStatusParser registrationOperationStatusParser = this.invokeRegister();

            log.info("Waiting for device provisioning service to provision this device...");
            this.executeStateMachineForStatus(registrationOperationStatusParser);
            this.close();
        }
        catch (ExecutionException | TimeoutException | ProvisioningDeviceClientException | SecurityProviderException e)
        {
            //SRS_ProvisioningTask_25_006: [ This method shall invoke the status callback, if any of the task fail or throw any exception. ]
            this.dpsStatus = PROVISIONING_DEVICE_STATUS_ERROR;
            invokeRegistrationCallback(new RegistrationResult(null, null, null, PROVISIONING_DEVICE_STATUS_ERROR), e);
            //SRS_ProvisioningTask_25_015: [ This method shall invoke close call on the contract and close the threads started.]
            this.close();
        }
        return null;
    }

    /**
     * This method shall shutdown the existing threads if not already done so.
     */
    private void close() throws ProvisioningDeviceConnectionException
    {
        provisioningDeviceClientContract.close();
        //SRS_ProvisioningTask_25_014: [ This method shall shutdown the executors if they have not already shutdown. ]
        if (executor != null && !executor.isShutdown())
        {
            executor.shutdownNow();
        }
    }
}
