package com.cvtailor.dto;

public class TailorRequest {

    private String jobDescription;

    public TailorRequest() {
    }

    public TailorRequest(String jobDescription) {
        this.jobDescription = jobDescription;
    }

    public String getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(String jobDescription) {
        this.jobDescription = jobDescription;
    }
}
