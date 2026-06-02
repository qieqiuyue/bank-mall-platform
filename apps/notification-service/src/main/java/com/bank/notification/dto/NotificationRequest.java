package com.bank.notification.dto;

public class NotificationRequest {
    private String accountNo;
    private String channel;
    private String template;
    private String title;
    private String content;

    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String a) { this.accountNo = a; }
    public String getChannel() { return channel; }
    public void setChannel(String c) { this.channel = c; }
    public String getTemplate() { return template; }
    public void setTemplate(String t) { this.template = t; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; }
}
