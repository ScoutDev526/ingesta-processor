package com.example.ingesta.core.service;

import com.example.ingesta.core.domain.model.Job;
import com.example.ingesta.core.domain.model.ProcessReport;

import java.util.List;

public interface JobProcessor {

    void process(List<Job> jobs);
}
