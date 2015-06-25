/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.authoriser;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import org.greenpole.entity.notification.NotificationWrapper;
import org.greenpole.entity.notification.SenderReceiverType;
import org.greenpole.hibernate.entity.Notification;
import org.greenpole.hibernate.query.GeneralComponentQuery;
import org.greenpole.hibernate.query.factory.ComponentQueryFactory;
import org.greenpole.util.Manipulator;
import org.greenpole.util.email.EmailClient;
import org.greenpole.util.properties.EmailProperties;
import org.greenpole.util.properties.NotificationProperties;
import org.greenpole.util.properties.ThreadPoolProperties;
import org.greenpole.util.email.TemplateReader;
import org.greenpole.util.properties.GreenpoleProperties;
import org.greenpole.util.threadfactory.GreenpoleNotifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Akinwale.Agbaje
 * Creates notifications on the database for authorisation requests.
 */
public class AuthoriserNotifier implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(AuthoriserNotifier.class);
    private final GeneralComponentQuery gq = ComponentQueryFactory.getGeneralComponentQuery();
    private final ThreadPoolProperties threadPoolProp = ThreadPoolProperties.getInstance();
    private final EmailProperties emailProp = EmailProperties.getInstance();
    private final GreenpoleProperties greenProp = GreenpoleProperties.getInstance();
    private final NotificationProperties notificationProp = NotificationProperties.getInstance();
    private final ExecutorService service;
    private final NotificationWrapper wrapper;
    private int POOL_SIZE;

    public AuthoriserNotifier(NotificationWrapper wrapper) {
        
        try {
            POOL_SIZE = Integer.parseInt(threadPoolProp.getAuthoriserNotifierPoolSize());
        } catch (Exception ex) {
            logger.info("Invalid property for pool size - see error log. Setting default size to 15");
            logger.error("Error assigning property value to pool size", ex);
            POOL_SIZE = 15;
        }
        
        service = Executors.newFixedThreadPool(POOL_SIZE, new GreenpoleNotifierFactory("AuthoriserNotifier-EmailClient"));
        this.wrapper = wrapper;
    }

    @Override
    public void run() {
        createNotification();
    }
    
    private void createNotification() {
        try {
            logger.info("setting extra information in wrapper");
            //set extra information
            SimpleDateFormat formatter = new SimpleDateFormat(greenProp.getDateFormat());
            wrapper.setFromType(SenderReceiverType.Internal.toString());
            wrapper.setToType(SenderReceiverType.Internal.toString());
            wrapper.setAttendedTo(false);
            wrapper.setSentDate(formatter.format(new Date()));
            
            logger.info("preparing notfication file");
            File file = new File(notificationProp.getNotificationLocation() + wrapper.getCode() + ".xml");
            file.getParentFile().mkdirs();
            
            JAXBContext jaxbContext = JAXBContext.newInstance(NotificationWrapper.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            jaxbMarshaller.marshal(wrapper, file);
            logger.info("notification file created - [{}.xml]", wrapper.getCode());
            
            //register notification in database
            Notification notification = new Notification(wrapper.getCode(), wrapper.getNotificationType(), 
                wrapper.getDescription(),wrapper.getFrom(), wrapper.getTo(),
                wrapper.getFromType(), wrapper.getToType(), wrapper.isAttendedTo(),
            false, false, null, new Date(), null);
            gq.createUpdateNotification(notification);
            logger.info("notification file registered in database");
            
            //send email notification
            Manipulator manipulate = new Manipulator();
            String[] to_names = manipulate.separateNameFromEmail(wrapper.getTo());
            String[] from_names = manipulate.separateNameFromEmail(wrapper.getFrom());
            
            String subject = "Authorisation requested";
            String templatePath = emailProp.getAuthorisationMailTemplate();
            
            String template = TemplateReader.getTemplateContent(templatePath);
            String to_person = to_names[0] + " " + to_names[1];
            String from_person = from_names[0] + " " + from_names[1];
            String body_main = "An authorisation request from " + from_person + " has been sent to you.<br>"
                    + "Please, log into Greenpole to attend to it.<br><br>"
                    + "Thank you.";
            
            EmailClient mailer = new EmailClient(emailProp.getMailSender(), wrapper.getTo(), subject, to_person, body_main, template);
            service.execute(mailer);
        } catch (JAXBException ex) {
            logger.info("an error occured while creating the notification file - [{}.xml]. See error log - ", wrapper.getCode());
            logger.error("a JAXBException was thrown by the authoriser notifier on creation of notification - [{}]", wrapper.getCode(), ex);
        } catch (Exception ex) {
            logger.info("an error occured while creating the notification file - [{}.xml]. See error log - ", wrapper.getCode());
            logger.error("a Exception was thrown by the authoriser notifier on creation of notification - [{}]", wrapper.getCode(), ex);
        }
    }
}
