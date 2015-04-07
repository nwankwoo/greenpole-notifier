/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.authoriser;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.Message;
import javax.jms.MessageListener;

/**
 *
 * @author Akinwale.Agbaje
 * Collects feeds authorisation requests to {@link AuthoriserNotifier}
 * @see AuthoriserNotifier
 */
@MessageDriven(mappedName = "jms/AuthorisationQueue", activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue")
})
public class AuthoriserNotifierQueue implements MessageListener {
    
    public AuthoriserNotifierQueue() {
    }
    
    @Override
    public void onMessage(Message message) {
        //implement method to utilise AuthoriserNotifier
    }
    
}
