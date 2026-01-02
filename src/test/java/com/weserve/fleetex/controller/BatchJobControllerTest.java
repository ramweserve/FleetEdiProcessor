package com.weserve.fleetex.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
public class BatchJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testTestEndpoint() throws Exception {
        mockMvc.perform(get("/api/batch/test"))
                .andExpect(status().isOk())
                .andExpect(content().string("Batch service is up and running!"));
    }

    @Test
    public void testUploadAndLoadFleetEdi() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-fleet.csv",
                "text/csv",
                "column1,column2\nvalue1,value2".getBytes()
        );

        mockMvc.perform(multipart("/api/batch/upload").file(file))
                .andExpect(status().isOk());
                //.andExpect(content().string(containsString("File uploaded and batch job invoked")));
    }
}
