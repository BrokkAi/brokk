package com.example.service;

public class NotificationService {
    
    public void sendEmail(String to, String subject, String body) {
        // Implementation would send email
        System.out.println("Sending email to: " + to);
        System.out.println("Subject: " + subject);
        System.out.println("Body: " + body);
    }
    
    public void sendSMS(String phoneNumber, String message) {
        // Implementation would send SMS
        System.out.println("Sending SMS to: " + phoneNumber);
        System.out.println("Message: " + message);
    }
    
    public void sendPushNotification(String userId, String message) {
        // Implementation would send push notification
        System.out.println("Sending push notification to user: " + userId);
        System.out.println("Message: " + message);
    }
}
