/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.textsender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.bind.JAXBException;
import org.greenpole.entity.sms.Result;
import org.greenpole.entity.sms.Results;
import org.greenpole.entity.sms.TextSend;
import org.greenpole.hibernate.entity.Holder;
import org.greenpole.hibernate.entity.TextMessage;
import org.greenpole.hibernate.query.GeneralComponentQuery;
import org.greenpole.hibernate.query.HolderComponentQuery;
import org.greenpole.hibernate.query.factory.ComponentQueryFactory;
import org.greenpole.util.email.EmailClient;
import org.greenpole.util.email.TemplateReader;
import org.greenpole.util.properties.EmailProperties;
import org.greenpole.util.properties.SMSProperties;
import org.greenpole.util.sms.SMSClient;
import org.greenpole.util.threadfactory.GreenpoleNotifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Akinwale Agbaje
 * 
 */
public class TextNotifier implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(TextNotifier.class);
    private final SMSProperties smsProp = SMSProperties.getInstance();
    private final EmailProperties emailProp = EmailProperties.getInstance();
    private final GeneralComponentQuery gq = ComponentQueryFactory.getGeneralComponentQuery();
    private final HolderComponentQuery hq = ComponentQueryFactory.getHolderComponentQuery();
    private final TextSend toSend;
    private final ExecutorService service;
    private final int POOL_SIZE = 20;

    public TextNotifier(TextSend toSend) {
        this.toSend = toSend;
        service = Executors.newFixedThreadPool(POOL_SIZE, new GreenpoleNotifierFactory("AuthoriserNotifier-EmailClient"));
    }

    @Override
    public void run() {
        if (toSend.isWithDbInfo())
            sendTextMessage();
        else
            sendTextMessageNoDBInteraction();
    }
    
    private void sendTextMessage() {
        SMSClient client = new SMSClient(smsProp.getAPIUsername(), smsProp.getAPIPassword());
        double balance = Double.valueOf(client.getCreditBalance());
        double ratePerText = Double.valueOf(smsProp.getTextRate());
        try {
            logger.info("recording text in database");
            if (toSend.isIsBulk()) {
                logger.info("sending bulk texts");
                List<TextMessage> texts = new ArrayList<>();
                for (Map.Entry pairs : toSend.getNumbersAndIds().entrySet()) {
                    String number = (String) pairs.getKey();
                    String messageId = (String) pairs.getValue();
                    
                    Holder holder = hq.getHolder(toSend.getHolderId());
                    
                    TextMessage text = new TextMessage();
                    text.setSender(toSend.getSender());
                    text.setReceiver(number);
                    text.setText(toSend.getText());
                    text.setPurpose(toSend.getPurpose());
                    text.setMessageId(messageId);
                    text.setIsBulk(toSend.isIsBulk());
                    text.setIsFlash(toSend.isIsFlash());
                    text.setSent(false);
                    text.setHolder(holder);
                    
                    texts.add(text);
                }
                
                boolean saved = gq.persistMultipleTextMessageRecord(texts);
                if (saved) {
                    double priceForTexts = ratePerText * texts.size();
                    if (balance >= priceForTexts) {
                        Results results = client.processSendTextBulk(toSend);
                        if (results.getResult() != null) {
                            
                            for (Result result : results.getResult()) {
                                String status = result.getStatus();
                                String msgId = result.getMessageid();
                                
                                if ("0".equals(status)) {
                                    logger.info("text message successfully sent - [{}]", result.getDestination());
                                    TextMessage savedTxt = gq.getTextMessageRecord(result.getMessageid());
                                    savedTxt.setSent(true);
                                    savedTxt.setMessageId(msgId);
                                    savedTxt.setReturnCode(Integer.valueOf(status));
                                    gq.persistTextMessageRecord(savedTxt);
                                } else if ("-5".equals(status)) {
                                    logger.info("text message not sent due to incorrect login credentials "
                                            + "received by messaging API - [{}]", result.getDestination());
                                } else if ("-13".equals(status)) {
                                    logger.info("text message successfully sent, though recepient number is invalid - [{}]", toSend.getPhoneNumber());
                                    TextMessage savedTxt = gq.getTextMessageRecord(result.getMessageid());
                                    savedTxt.setSent(true);
                                    savedTxt.setMessageId(msgId);
                                    savedTxt.setReturnCode(Integer.valueOf(status));
                                    gq.persistTextMessageRecord(savedTxt);
                                    
                                    //send email warning to system administrator
                                    Holder holder = hq.getHolder(savedTxt.getHolder().getId());
                                    String body_main = "The text message sent to " + holder.getFirstName() + " " + holder.getLastName()
                                            + " was not delivered due to an incorrect phone set for him.<br>"
                                            + "This error should be corrected. The system will automatically try to "
                                            + "resend the text message in minutes<br><br>"
                                            + "Thank you.";
                                    sendErrorMail(body_main);
                                }
                            }
                        } else {
                            logger.info("no result from text messaging API");
                        }
                    } else {
                        logger.info("WARNING:: insufficient balance to send text");
                        //send email warning to system administrator
                        String body_main = "The system cannot send text messages due to insufficient balance on the API's side. Please, top-up.<br>"
                                + "The system will automatically try to resend text messages in minutes<br><br>"
                                + "Thank you.";
                        sendErrorMail(body_main);
                    }
                }
            } else {
                logger.info("sending single text");
                
                Holder holder = hq.getHolder(toSend.getHolderId());
                
                TextMessage text = new TextMessage();
                text.setSender(toSend.getSender());
                text.setReceiver(toSend.getPhoneNumber());
                text.setText(toSend.getText());
                text.setPurpose(toSend.getPurpose());
                text.setMessageId(toSend.getMessage_id());
                text.setIsBulk(toSend.isIsBulk());
                text.setIsFlash(toSend.isIsFlash());
                text.setSent(false);
                text.setHolder(holder);
                boolean saved = gq.persistTextMessageRecord(text);
                
                if (saved) {
                    if (balance >= ratePerText) {
                        Results result = client.processSendTextSingle(toSend);
                        if (result.getResult() != null) {
                            String status = result.getResult().get(0).getStatus();
                            String msgId = result.getResult().get(0).getMessageid();

                            if ("0".equals(status)) {
                                logger.info("text message successfully sent - [{}]", result.getResult().get(0).getDestination());
                                TextMessage savedTxt = gq.getTextMessageRecord(result.getResult().get(0).getMessageid());
                                savedTxt.setSent(true);
                                savedTxt.setMessageId(msgId);
                                savedTxt.setReturnCode(Integer.valueOf(status));
                                gq.persistTextMessageRecord(savedTxt);
                            } else if ("-5".equals(status)) {
                                logger.info("text message not sent due to incorrect login credentials "
                                        + "received by messaging API - [{}]", result.getResult().get(0).getDestination());
                            } else if ("-13".equals(status)) {
                                logger.info("text message successfully sent, though recepient number is invalid - [{}]", result.getResult().get(0).getDestination());
                                TextMessage savedTxt = gq.getTextMessageRecord(result.getResult().get(0).getMessageid());
                                savedTxt.setSent(true);
                                savedTxt.setMessageId(msgId);
                                savedTxt.setReturnCode(Integer.valueOf(status));
                                gq.persistTextMessageRecord(savedTxt);
                                //send email warning to system administrator
                                String body_main = "The text message sent to " + holder.getFirstName() + " " + holder.getLastName()
                                        + " was not delivered due to an incorrect phone set for him.<br>"
                                        + "This error should be corrected. The system will automatically try to "
                                        + "resend the text message in minutes<br><br>"
                                        + "Thank you.";
                                sendErrorMail(body_main);
                            }
                        } else {
                            logger.info("no result from text messaging API");
                        }
                    } else {
                        logger.info("WARNING:: insufficient balance to send text");
                        //send email warning to system administrator
                        String body_main = "The system cannot send text messages due to insufficient balance on the API's side. Please, top-up.<br>"
                                + "The system will automatically try to resend text messages in minutes<br><br>"
                                + "Thank you.";
                        sendErrorMail(body_main);
                    }
                } else {
                    logger.info("text message not sent. Text record was not saved");
                }
            }
        } catch (JAXBException ex) {
            logger.info("an error occured while creating text message xml file. See error log - ", toSend.getMessage_id());
            logger.error("a JAXBException was thrown by the text notifier on creation of text message xml file - [{}]", toSend.getMessage_id(), ex);
        } catch (Exception ex) {
            logger.info("an error occured while creating text message xml file. See error log - ", toSend.getMessage_id());
            logger.error("an Exception was thrown by the text notifier on creation of text message xml file - [{}]", toSend.getMessage_id(), ex);
        }
    }
    
    private void sendTextMessageNoDBInteraction() {
        SMSClient client = new SMSClient(smsProp.getAPIUsername(), smsProp.getAPIPassword());
        double balance = Double.valueOf(client.getCreditBalance());
        double ratePerText = Double.valueOf(smsProp.getTextRate());
        try {
            logger.info("recording text in database");
            if (toSend.isIsBulk()) {
                logger.info("sending bulk texts");
                List<TextMessage> texts = new ArrayList<>();
                for (Map.Entry pairs : toSend.getNumbersAndIds().entrySet()) {
                    String number = (String) pairs.getKey();
                    String messageId = (String) pairs.getValue();
                    
                    //Holder holder = hq.getHolder(toSend.getHolderId());
                    
                    TextMessage text = new TextMessage();
                    text.setSender(toSend.getSender());
                    text.setReceiver(number);
                    text.setText(toSend.getText());
                    text.setPurpose(toSend.getPurpose());
                    text.setMessageId(messageId);
                    text.setIsBulk(toSend.isIsBulk());
                    text.setIsFlash(toSend.isIsFlash());
                    text.setSent(false);
                    text.setMonitor(false);
                    //text.setHolder(holder);
                    
                    texts.add(text);
                }
                
                boolean saved = gq.persistMultipleTextMessageRecord(texts);
                if (saved) {
                    double priceForTexts = ratePerText * texts.size();
                    if (balance >= priceForTexts) {
                        Results results = client.processSendTextBulk(toSend);
                        if (results.getResult() != null) {
                            
                            for (Result result : results.getResult()) {
                                String status = result.getStatus();
                                String msgId = result.getMessageid();
                                String number = result.getDestination();
                                
                                if ("0".equals(status)) {
                                    logger.info("text message successfully sent - [{}]", result.getDestination());
                                    TextMessage savedTxt = gq.getTextMessageRecord(result.getMessageid());
                                    savedTxt.setSent(true);
                                    savedTxt.setMessageId(msgId);
                                    savedTxt.setReturnCode(Integer.valueOf(status));
                                    gq.persistTextMessageRecord(savedTxt);
                                } else if ("-5".equals(status)) {
                                    logger.info("text message not sent due to incorrect login credentials "
                                            + "received by messaging API - [{}]", result.getDestination());
                                } else if ("-13".equals(status)) {
                                    logger.info("text message successfully sent, though recepient number is invalid - [{}]", toSend.getPhoneNumber());
                                    TextMessage savedTxt = gq.getTextMessageRecord(result.getMessageid());
                                    savedTxt.setSent(true);
                                    savedTxt.setMessageId(msgId);
                                    savedTxt.setReturnCode(Integer.valueOf(status));
                                    gq.persistTextMessageRecord(savedTxt);
                                    
                                    //send email warning to system administrator
                                    String body_main = "The text message sent to " + number
                                            + " was not delivered because the number is incorrect.<br>"
                                            + "Thank you.";
                                    sendErrorMail(body_main);
                                }
                            }
                        } else {
                            logger.info("no result from text messaging API");
                        }
                    } else {
                        logger.info("WARNING:: insufficient balance to send text");
                        //send email warning to system administrator
                        String body_main = "The system cannot send text messages due to insufficient balance on the API's side. Please, top-up.<br>"
                                + "The system will automatically try to resend text messages in minutes<br><br>"
                                + "Thank you.";
                        sendErrorMail(body_main);
                    }
                }
            } else {
                logger.info("sending single text");
                
                //Holder holder = hq.getHolder(toSend.getHolderId());
                
                TextMessage text = new TextMessage();
                text.setSender(toSend.getSender());
                text.setReceiver(toSend.getPhoneNumber());
                text.setText(toSend.getText());
                text.setPurpose(toSend.getPurpose());
                text.setMessageId(toSend.getMessage_id());
                text.setIsBulk(toSend.isIsBulk());
                text.setIsFlash(toSend.isIsFlash());
                text.setSent(false);
                text.setMonitor(false);
                //text.setHolder(holder);
                boolean saved = gq.persistTextMessageRecord(text);
                
                if (saved) {
                    if (balance >= ratePerText) {
                        Results result = client.processSendTextSingle(toSend);
                        if (result.getResult() != null) {
                            String status = result.getResult().get(0).getStatus();
                            String msgId = result.getResult().get(0).getMessageid();
                            String number = result.getResult().get(0).getDestination();

                            if ("0".equals(status)) {
                                logger.info("text message successfully sent - [{}]", result.getResult().get(0).getDestination());
                                TextMessage savedTxt = gq.getTextMessageRecord(result.getResult().get(0).getMessageid());
                                savedTxt.setSent(true);
                                savedTxt.setMessageId(msgId);
                                savedTxt.setReturnCode(Integer.valueOf(status));
                                gq.persistTextMessageRecord(savedTxt);
                            } else if ("-5".equals(status)) {
                                logger.info("text message not sent due to incorrect login credentials "
                                        + "received by messaging API - [{}]", result.getResult().get(0).getDestination());
                            } else if ("-13".equals(status)) {
                                logger.info("text message successfully sent, though recepient number is invalid - [{}]", result.getResult().get(0).getDestination());
                                TextMessage savedTxt = gq.getTextMessageRecord(result.getResult().get(0).getMessageid());
                                savedTxt.setSent(true);
                                savedTxt.setMessageId(msgId);
                                savedTxt.setReturnCode(Integer.valueOf(status));
                                gq.persistTextMessageRecord(savedTxt);
                                //send email warning to system administrator
                                String body_main = "The text message sent to " + number
                                            + " was not delivered because the number is incorrect.<br>"
                                            + "Thank you.";
                                sendErrorMail(body_main);
                            }
                        } else {
                            logger.info("no result from text messaging API");
                        }
                    } else {
                        logger.info("WARNING:: insufficient balance to send text");
                        //send email warning to system administrator
                        String body_main = "The system cannot send text messages due to insufficient balance on the API's side. Please, top-up.<br>"
                                + "The system will automatically try to resend text messages in minutes<br><br>"
                                + "Thank you.";
                        sendErrorMail(body_main);
                    }
                } else {
                    logger.info("text message not sent. Text record was not saved");
                }
            }
        } catch (JAXBException ex) {
            logger.info("an error occured while creating text message xml file. See error log - ", toSend.getMessage_id());
            logger.error("a JAXBException was thrown by the text notifier on creation of text message xml file - [{}]", toSend.getMessage_id(), ex);
        } catch (Exception ex) {
            logger.info("an error occured while creating text message xml file. See error log - ", toSend.getMessage_id());
            logger.error("an Exception was thrown by the text notifier on creation of text message xml file - [{}]", toSend.getMessage_id(), ex);
        }
    }

    private void sendErrorMail(String message) {
        String subject = "Invalid Recepient number";
        String templatePath = emailProp.getWarningMailTemplate();
        
        String template = TemplateReader.getTemplateContent(templatePath);
        String to_person = "Admin";
        
        EmailClient mailer = new EmailClient(emailProp.getMailSender(), emailProp.getMailSender(),
                subject, to_person, message, template);
        service.execute(mailer);
    }
}
