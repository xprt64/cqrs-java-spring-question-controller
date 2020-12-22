package com.cqrs.spring_question_controller;

import com.cqrs.aggregates.AggregateCommandHandlingException;
import com.cqrs.base.Question;
import com.cqrs.questions.AnnotatedQuestionAnswerersMap;
import com.cqrs.questions.Asker;
import com.cqrs.questions.QuestionRejectedByValidators;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.stream.Collectors;

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
            return asker.askAndReturn(deserialize(requestBody));
        } catch (QuestionRejectedByValidators e) {
            throw new ExceptionCaught(HttpStatus.BAD_REQUEST, e);
        } catch (Throwable e) {
            throw new ExceptionCaught(HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    private Question deserialize(Body requestBody) throws com.fasterxml.jackson.core.JsonProcessingException, ClassNotFoundException {
        return (Question) deserializer.readValue(requestBody.payload, Class.forName(requestBody.type));
    }

    @ExceptionHandler(QuestionRejectedByValidators.class)
    public ResponseEntity<ReturnedError> error(QuestionRejectedByValidators ex) {
        System.out.println( String.format("error: %s: %s", ex.getCause().getClass().getCanonicalName(), ex.getCause().getMessage()));
        return new ResponseEntity<>(
            new ReturnedError(ex.getErrors().stream().map(e -> new Error(e.getClass().getCanonicalName(), e.getMessage())).toArray(Error[]::new)),
            HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(ExceptionCaught.class)
    public ResponseEntity<ReturnedError> error(ExceptionCaught ex) {
        System.out.println( String.format("error: %s: %s", ex.getCause().getClass().getCanonicalName(), ex.getCause().getMessage()));
        return new ResponseEntity<>(
            new ReturnedError(new Error(ex.getCause().getClass().getCanonicalName(), ex.getCause().getMessage())),
            ex.code
        );
    }

    @CrossOrigin
    @PostMapping("/ask")
    public void dispatchAndReturnEvents(@RequestBody Body requestBody, HttpServletResponse response) throws IOException, ExceptionCaught {
        if (annotatedQuestionAnswerersMap.getMap().get(requestBody.type.replace('$', '.')) == null) {
            throw new ExceptionCaught(HttpStatus.BAD_REQUEST, new InvalidParameterException("Question class not valid"));
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

    @GetMapping("/index")
    public void help(HttpServletResponse response) throws IOException, ExceptionCaught {
        ObjectNode objectNode = serializer.createObjectNode();
        objectNode.put("description", "answers questions");
        objectNode.put("validQuestions", String.join(", ", annotatedQuestionAnswerersMap.getMap().keySet().stream().collect(Collectors.toList())));

        response.setContentType("application/json");
        try {
            response.getOutputStream().write(objectNode.toPrettyString().getBytes());
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
        public final String type;
        public final String message;

        public Error(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    private static class ReturnedError {
        public final Error[] errors;

        public ReturnedError(Error[] errors) {
            this.errors= errors;
        }

        public ReturnedError(Error error) {
            this.errors= new Error[]{ error };
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
