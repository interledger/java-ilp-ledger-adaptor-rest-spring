package org.interledger.ilp.ledger.adaptor.rest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.interledger.cryptoconditions.Fulfillment;
import org.interledger.ilp.core.InterledgerAddress;
import org.interledger.ilp.core.ledger.LedgerAdaptor;
import org.interledger.ilp.core.ledger.events.LedgerEventHandler;
import org.interledger.ilp.core.ledger.model.AccountInfo;
import org.interledger.ilp.core.ledger.model.LedgerInfo;
import org.interledger.ilp.core.ledger.model.LedgerMessage;
import org.interledger.ilp.core.ledger.model.LedgerTransfer;
import org.interledger.ilp.core.ledger.model.TransferRejectedReason;
import org.interledger.ilp.ledger.adaptor.rest.exceptions.RestServiceException;
import org.interledger.ilp.ledger.adaptor.rest.service.RestLedgerAccountService;
import org.interledger.ilp.ledger.adaptor.rest.service.RestLedgerAuthTokenService;
import org.interledger.ilp.ledger.adaptor.rest.service.RestLedgerJsonConverter;
import org.interledger.ilp.ledger.adaptor.rest.service.RestLedgerMessageService;
import org.interledger.ilp.ledger.adaptor.rest.service.RestLedgerMetaService;
import org.interledger.ilp.ledger.adaptor.rest.service.RestLedgerTransferService;
import org.interledger.ilp.ledger.adaptor.ws.JsonRpcLedgerWebSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class RestLedgerAdaptor implements LedgerAdaptor {

  private static final Logger log = LoggerFactory.getLogger(RestLedgerAdaptor.class);

  private UsernamePasswordAuthenticationToken accountAuthToken = null;
  private RestLedgerAccountService accountService;
  private RestLedgerAuthTokenService authTokenService;
  private RestLedgerTransferService transferService;
  private RestLedgerMessageService messageService;

  private RestLedgerMetaService metaService;

  private JsonRpcLedgerWebSocketChannel websocketChannel;

  private RestTemplateBuilder restTemplateBuilder;

  private LedgerEventHandler eventhandler;

  private Set<InterledgerAddress> connectors;

  private RestLedgerJsonConverter converter;

  public RestLedgerAdaptor(RestTemplateBuilder restTemplateBuilder, URI ledgerBaseUrl) {
    
    this.restTemplateBuilder = restTemplateBuilder;
    this.metaService = new RestLedgerMetaService(restTemplateBuilder.build(), ledgerBaseUrl);
  }

  /**
   * The REST adaptor performs the following steps when it connects
   * <ol>
   * <li>Execute a GET against the base URL to get the ledger meta-data</li>
   * <li>Asynchronously opn a web socket to the server.
   * <ol>
   * <li>Get an auth token.</li>
   * <li>Attempt to establish a connection.</li>
   * </ol>
   * <li>
   * </ol>
   */
  @Override
  public void connect() {

    metaService.getLedgerInfo(true);
    converter = metaService.getConverter();

    // Connect to socket for events
    createWebsocket(metaService.getWebsocketUri());
    websocketChannel.open();

  }

  @Override
  public void disconnect() {

    throwIfNotConnected();

    // Disconnect websocket
    try {
      this.websocketChannel.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Error while closing websocket.", e);
    }
    this.websocketChannel = null;

    // Reset meta-data service
    metaService = new RestLedgerMetaService(restTemplateBuilder.build(), metaService.getBaseUri());
 
    //Clear connector list
    connectors = null;
  }

  @Override
  public Set<InterledgerAddress> getConnectors() {
    
    throwIfNotConnected();

    if(connectors == null) {
      Set<URI> connectorIds = metaService.getConnectorIds();
      connectors = new HashSet<InterledgerAddress>(connectorIds.size());
      for (URI uri : connectorIds) {
        connectors.add(converter.convertAccountUriToAddress(uri));
      }
    }
    
    return Collections.unmodifiableSet(connectors);
  }

  @Override
  public AccountInfo getAccountInfo(InterledgerAddress account) {

    URI accountId = converter.convertAccountAddressToUri(account);
    return getAccountService().getAccountInfo(accountId);
  }

  public void fulfillTransfer(UUID transferId, Fulfillment fulfillment) {
    URI transferIdUri = converter.convertTransferUuidToUri(transferId);
    getTransferService().fulfillTransfer(transferIdUri, fulfillment);
  }
  
  @Override
  public LedgerInfo getLedgerInfo() {

    throwIfNotConnected();

    return metaService.getLedgerInfo();
  }

  public boolean isConnected() {
    return (this.websocketChannel != null && this.websocketChannel.isOpen());
  }

  @Override
  public void rejectTransfer(LedgerTransfer transfer, TransferRejectedReason reason) {
    getTransferService().rejectTransfer(transfer, reason);
  }

  @Override
  public void sendMessage(LedgerMessage msg) {

    throwIfNotConnected();

    if (messageService == null) {
      messageService = new RestLedgerMessageService(
          converter, 
          getRestTemplateBuilderWithAuthIfAvailable().build(), 
          metaService.getMessageUri());
    }

    messageService.sendMessage(msg);

  }

  @Override
  public void sendTransfer(LedgerTransfer transfer) {
    getTransferService().sendTransfer(transfer);
  }

  @Autowired(required = false)
  public void setAccountAuthToken(UsernamePasswordAuthenticationToken accountAuthToken) {
    this.accountAuthToken = accountAuthToken;
  }

  @Override
  public void setEventHandler(LedgerEventHandler eventHandler) {
    this.eventhandler = eventHandler;
  }

  @Override
  public void subscribeToAccountNotifications(InterledgerAddress account) {
    URI accountId = converter.convertAccountAddressToUri(account);
    getAccountService().subscribeToAccountNotifications(accountId);
  }

  private void createWebsocket(URI wsUri) throws RestServiceException {

    if (this.websocketChannel == null || !this.websocketChannel.isOpen()) {

      if (this.authTokenService == null) {
        this.authTokenService = new RestLedgerAuthTokenService(
            getRestTemplateBuilderWithAuthIfAvailable().build(),
            metaService.getAuthTokenUri());
      }

      String token = this.authTokenService.getAuthToken();

      log.debug("Creating Notification Listener Service");

      if (wsUri == null || wsUri.getScheme() == null || !wsUri.getScheme().startsWith("ws")) {
        throw new RuntimeException("Invalid websocket URL: " + wsUri);
      }

      this.websocketChannel = new JsonRpcLedgerWebSocketChannel(wsUri, token, eventhandler, converter);
    }

  }

  private RestLedgerAccountService getAccountService() {

    throwIfNotConnected();

    if (this.accountService == null) {
      log.debug("Creating Account Service");
      this.accountService = new RestLedgerAccountService(converter,
          getRestTemplateBuilderWithAuthIfAvailable().build(), this.websocketChannel);
    }

    return this.accountService;

  }

  private RestTemplateBuilder getRestTemplateBuilderWithAuthIfAvailable() {

    if (accountAuthToken != null
        && (accountAuthToken.getPrincipal() != null && accountAuthToken.getCredentials() != null)) {

      return restTemplateBuilder.basicAuthorization(accountAuthToken.getPrincipal().toString(),
          accountAuthToken.getCredentials().toString());

    }

    return restTemplateBuilder;

  }

  private RestLedgerTransferService getTransferService() {

    throwIfNotConnected();

    if (this.transferService == null) {
      log.debug("Creating Transfer Service");
      this.transferService =
          new RestLedgerTransferService(
              converter, 
              getRestTemplateBuilderWithAuthIfAvailable().build());
    }

    return this.transferService;

  }

  private void throwIfNotConnected() {
    if (!isConnected()) {
      throw new RuntimeException("LedgerClient is not connected.");
    }
  }

}
