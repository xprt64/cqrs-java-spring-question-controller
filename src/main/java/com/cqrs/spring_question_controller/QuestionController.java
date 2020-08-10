package com.cqrs.spring_question_controller;

import com.cqrs.aggregates.AggregateCommandHandlingException;
import com.cqrs.questions.AnnotatedQuestionAnswerersMap;
import com.cqrs.questions.Asker;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.InvalidParameterException;

@Controller
@RequestMapping("/question")
public class QuestionController {

    private final AnnotatedQuestionAnswerersMap annotatedQuestionAnswerersMap;
    private final ObjectMapper deserializer;
    private final ObjectMapper serializer;
    private final Asker asker;

    public QuestionController(
        Asker asker,
        AnnotatedQuestionAnswerersMap annotatedQuestionAnswerersMap,
        ObjectMapper objectMapper
    ) {
        this.asker = asker;
        this.annotatedQuestionAnswerersMap = annotatedQuestionAnswerersMap;
        this.deserializer = objectMapper;
        this.serializer = serializer();
    }

    private Object askQuestion(Body requestBody) {
        try {
            System.out.println("asking question " + requestBody.type);
            return asker.askAndReturn(deserializer.readValue(requestBody.payload, Class.forName(requestBody.type)));
        } catch (AggregateCommandHandlingException e) {
            e.printStackTrace();
            throw new ExceptionCaught(HttpStatus.INTERNAL_SERVER_ERROR, e.getCause());
        } catch (Throwable e) {
            e.printStackTrace();
            throw new ExceptionCaught(HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    @ExceptionHandler(ExceptionCaught.class)
    public ResponseEntity<Error> error(ExceptionCaught ex) {
        return new ResponseEntity<Error>(
            new Error(ex.getCause().getClass().getCanonicalName(), ex.getCause().getMessage()),
            ex.code
        );
    }

    @PostMapping("/ask")
    public void dispatchAndReturnEvents(@RequestBody Body requestBody, HttpServletResponse response) throws IOException, ExceptionCaught {
        if (annotatedQuestionAnswerersMap.getMap().get(requestBody.type.replace('$', '.')) == null) {
            throw new InvalidParameterException("Question class not valid");
        }
        Object answeredQuestion = askQuestion(requestBody);
        String jsonResponse = serializer.writeValueAsString(answeredQuestion);
        response.setContentType("application/json");
        try {
            response.getOutputStream().write(jsonResponse.getBytes());
        } finally {
            response.getOutputStream().close();
        }
    }

    private ObjectMapper serializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES));
        mapper.findAndRegisterModules();
        //mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    public static class Body {
        public String payload;
        public String type;
    }

    private static class Error {
        public String type;
        public String message;

        public Error(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    private static class ExceptionCaught extends RuntimeException {
        private HttpStatus code;

        ExceptionCaught(HttpStatus code, Throwable cause) {
            super(cause);
            this.code = code;
        }
    }
}
