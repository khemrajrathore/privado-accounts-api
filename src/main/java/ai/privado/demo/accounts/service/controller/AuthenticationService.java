package ai.privado.demo.accounts.service.controller;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.slack.api.Slack;

import ai.privado.demo.accounts.apistubs.DataLoggerS;
import ai.privado.demo.accounts.service.dto.LoginD;
import ai.privado.demo.accounts.service.dto.SignupD;
import ai.privado.demo.accounts.service.dto.UserProfileD;
import ai.privado.demo.accounts.service.entity.SessionE;
import ai.privado.demo.accounts.service.entity.UserE;
import ai.privado.demo.accounts.service.repos.SessionsR;
import ai.privado.demo.accounts.service.repos.UserRepository;
import ai.privado.demo.accounts.thirdparty.SendGridStub;
import ai.privado.demo.accounts.thirdparty.SlackStub;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;

@RestController
@RequestMapping("/api/public/user")
public class AuthenticationService {
	private static Logger logger = LoggerFactory.getLogger(AuthenticationService.class);
	private final UserRepository userr;
	private final SessionsR sesr;
	private final ModelMapper mapper;
	private ExecutorService apiExecutor;
	private DataLoggerS datalogger;
	private ObjectMapper objectMapper;
	private SlackStub slackStub;
	private SendGridStub sgStub;

	@Autowired
	public AuthenticationService(UserRepository userr, SessionsR sesr, ModelMapper mapper, DataLoggerS datalogger,
			ObjectMapper objectMapper, @Qualifier("ApiCaller") ExecutorService apiExecutor, SlackStub slackStub,
			SendGridStub sgStub) {
		super();
		this.userr = userr;
		this.sesr = sesr;
		this.mapper = mapper;
		this.datalogger = datalogger;
		this.objectMapper = objectMapper;
		this.apiExecutor = apiExecutor;
		this.slackStub = slackStub;
		this.sgStub = sgStub;
	}

	@PostMapping("/signup")
	public UserProfileD signup(@RequestBody SignupD signup) {
		if (signup != null && signup.getEmail() != null && signup.getPassword() != null && !signup.getEmail().isEmpty()
				&& !signup.getPassword().isEmpty()) {

			String email = signup.getEmail();
			String phone = signup.getPhone();
			String firstName = signup.getFirstName();
			String lastName = signup.getLastName();
			String password = signup.getPassword();
			UserE us = new UserE();
			us.setEmail(email);
			us.setFirstName(firstName);
			us.setLastName(lastName);
			us.setPassword(password);
			us.setPhone(phone);
			UserE saved = userr.save(us);
			logger.info("New Signup : - " + email + phone);
			this.sendEvent(UUID.randomUUID().toString(), "SIGNUP", email + phone);
			this.sendEmail(email, "Welcome", "Hi " + firstName + " " + lastName + " Some welcome message to begin");
			this.sendSlackMessage("someid", "New user Signup - " + email + ", Name - " + firstName + " " + lastName);
			return mapper.map(saved, UserProfileD.class);
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
	}

	@PostMapping("/authenticate")
	public String authenticate(@RequestBody LoginD login) {
		if (login != null && login.getEmail() != null && login.getPassword() != null && !login.getEmail().isBlank()
				&& !login.getPassword().isBlank()) {
			Optional<UserE> resp = userr.findByEmail(login.getEmail());

			String email = login.getEmail();
			String password = login.getPassword();
			logger.info("Login request : - " + email + "-" + password);
			this.sendEvent(UUID.randomUUID().toString(), "LOGIN", "Login request : - " + email + "-" + password);
			if (!resp.isEmpty() && login.getPassword().equals(resp.get().getPassword())) {
				SessionE ses = new SessionE();
				ses.setUserId(resp.get().getId());
				ses = sesr.save(ses);
				return ses.getId();
			}
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
	}

	public void sendEmail(String toemail, String subject, String body) {
		Email from = new Email("test@privado.ai");
		Email to = new Email(toemail);
		Content content = new Content("text/plain", body);
		Mail mails = new Mail(from, subject, to, content);

		SendGrid sg = new SendGrid("Dummy-api-key");
		Request request = new Request();
		try {
			request.setMethod(Method.POST);
			request.setEndpoint("mail/send");
			request.setBody(mails.build());
			Response response = sg.api(request);
			System.out.println(response.getStatusCode());
			System.out.println(response.getBody());
			System.out.println(response.getHeaders());
		} catch (IOException ex) {
			logger.error("Error sending email:", ex);
		}
	}

	public void sendSlackMessage(String id, String message) {
		var slackWebHookURL = "https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX";

		var client = Slack.getInstance();
		try {
			// Call the chat.postMessage method using the built-in WebClient
			var result = client.send(slackWebHookURL, message);

			// Print result, which includes information about the message (like TS)
			logger.info("result {}", result);
		} catch (Exception e) {
			logger.error("error: {}", e.getMessage(), e);
		}
	}

	public void sendEvent(String id, String event, String eventData) {
		String baseURL = "https://localhost/analytics";

		try {
			String payload = objectMapper.writeValueAsString(eventData);
			// TODO: pickup the base URL from application.properties
			HttpResponse<String> response = Unirest.post(baseURL + "/events").header("accept", "application/json")
					.header("Content-Type", "application/json").body(payload).asString();

			if (response.getStatus() != 200) {
				logger.error("Error in event logging..");
			} else {
				logger.info("Event logging successful");
			}
		} catch (UnirestException | IOException e) {
			logger.error("Event log error:", e);
		}
	}
}
