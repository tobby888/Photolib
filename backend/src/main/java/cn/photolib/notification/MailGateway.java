package cn.photolib.notification;

public interface MailGateway {
    void send(String to, String subject, String html);
}
