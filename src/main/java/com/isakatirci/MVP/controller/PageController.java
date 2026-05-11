package com.isakatirci.MVP.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/transfer-form")
    public String transferFormPage() {
        return "transfer-form";
    }
}
