package password.pwm.http.servlet;

import com.novell.ldapchai.exception.ChaiUnavailableException;
import lombok.Data;
import password.pwm.AppProperty;
import password.pwm.Permission;
import password.pwm.PwmApplication;
import password.pwm.PwmApplicationMode;
import password.pwm.PwmConstants;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.SelectableContextMode;
import password.pwm.config.value.data.ActionConfiguration;
import password.pwm.config.value.data.FormConfiguration;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.HttpHeader;
import password.pwm.http.HttpMethod;
import password.pwm.http.IdleTimeoutCalculator;
import password.pwm.http.ProcessStatus;
import password.pwm.http.PwmHttpRequestWrapper;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmSession;
import password.pwm.http.PwmURL;
import password.pwm.i18n.Display;
import password.pwm.svc.stats.EpsStatistic;
import password.pwm.svc.stats.Statistic;
import password.pwm.util.LocaleHelper;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroMachine;
import password.pwm.util.secure.PwmHashAlgorithm;
import password.pwm.util.secure.SecureEngine;
import password.pwm.ws.server.RestResultBean;
import password.pwm.ws.server.rest.RestHealthServer;
import password.pwm.ws.server.rest.bean.HealthData;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.TreeSet;

@WebServlet(
        name="ClientApiServlet",
        urlPatterns = {
                PwmConstants.URL_PREFIX_PUBLIC + "/api",
        }
)
public class ClientApiServlet extends ControlledPwmServlet {

    private static final PwmLogger LOGGER = PwmLogger.forClass(ClientApiServlet.class);

    @Data
    public static class AppData implements Serializable {
        public Map<String,Object> PWM_GLOBAL;
    }

    @Data
    public static class PingResponse implements Serializable {
        private Instant time;
        private String runtimeNonce;
    }

    public enum ClientApiAction implements AbstractPwmServlet.ProcessAction {
        clientData(HttpMethod.GET),
        strings(HttpMethod.GET),
        health(HttpMethod.GET),
        ping(HttpMethod.GET),

        ;

        private final HttpMethod method;

        ClientApiAction(final HttpMethod method)
        {
            this.method = method;
        }

        public Collection<HttpMethod> permittedMethods()
        {
            return Collections.singletonList(method);
        }
    }

    @Override
    public Class<? extends ProcessAction> getProcessActionsClass() {
        return ClientApiServlet.ClientApiAction.class;
    }

    @Override
    protected void nextStep(final PwmRequest pwmRequest) throws PwmUnrecoverableException, IOException, ChaiUnavailableException, ServletException {
        // no mvc pattern in this servlet
    }

    @Override
    public ProcessStatus preProcessCheck(final PwmRequest pwmRequest) throws PwmUnrecoverableException, IOException, ServletException {
        return ProcessStatus.Continue;
    }


    @ActionHandler(action = "clientData")
    public ProcessStatus processRestClientData(final PwmRequest pwmRequest)
            throws PwmUnrecoverableException, IOException, ChaiUnavailableException
    {
        final String pageUrl = pwmRequest.readParameterAsString("pageUrl", PwmHttpRequestWrapper.Flag.BypassValidation);
        final String etagParam = pwmRequest.readParameterAsString("etag", PwmHttpRequestWrapper.Flag.BypassValidation);

        final int maxCacheAgeSeconds = 60 * 5;

        final String eTagValue = makeClientEtag(pwmRequest.getPwmApplication(), pwmRequest.getPwmSession(), pwmRequest.getHttpServletRequest());

        // check the incoming header;
        final String ifNoneMatchValue = pwmRequest.readHeaderValueAsString("If-None-Match");

        if (ifNoneMatchValue != null && ifNoneMatchValue.equals(eTagValue) && eTagValue.equals(etagParam)) {
            pwmRequest.getPwmResponse().setStatus(304);
            return ProcessStatus.Halt;
        }

        pwmRequest.getPwmResponse().setHeader(HttpHeader.ETag, eTagValue);
        pwmRequest.getPwmResponse().setHeader(HttpHeader.Expires, String.valueOf(System.currentTimeMillis() + (maxCacheAgeSeconds * 1000)));
        pwmRequest.getPwmResponse().setHeader(HttpHeader.Cache_Control, "public, max-age=" + maxCacheAgeSeconds);

        final AppData appData = makeAppData(pwmRequest.getPwmApplication(), pwmRequest.getPwmSession(), pwmRequest.getHttpServletRequest(), pwmRequest.getPwmResponse().getHttpServletResponse(), pageUrl);
        final RestResultBean restResultBean = RestResultBean.withData(appData);
        pwmRequest.outputJsonResult(restResultBean);
        return ProcessStatus.Halt;
    }

    @ActionHandler(action = "strings")
    public ProcessStatus doGetStringsData(final PwmRequest pwmRequest
    )
            throws PwmUnrecoverableException, IOException, ChaiUnavailableException, ServletException {
        final String bundleName = pwmRequest.readParameterAsString("bundle");
        final int maxCacheAgeSeconds = 60 * 5;

        final String eTagValue = makeClientEtag(pwmRequest.getPwmApplication(), pwmRequest.getPwmSession(), pwmRequest.getHttpServletRequest());

        pwmRequest.getPwmResponse().setHeader(HttpHeader.ETag, eTagValue);
        pwmRequest.getPwmResponse().setHeader(HttpHeader.Expires, String.valueOf(System.currentTimeMillis() + (maxCacheAgeSeconds * 1000)));
        pwmRequest.getPwmResponse().setHeader(HttpHeader.Cache_Control, "public, max-age=" + maxCacheAgeSeconds);

        try {
            final LinkedHashMap<String,String> displayData = new LinkedHashMap<>(makeDisplayData(pwmRequest.getPwmApplication(),
                    pwmRequest.getPwmSession(), bundleName));
            final RestResultBean restResultBean = RestResultBean.withData(displayData);
            pwmRequest.outputJsonResult(restResultBean);
        } catch (Exception e) {
            final String errorMSg = "error during rest /strings call for bundle " + bundleName + ", error: " + e.getMessage();
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNKNOWN,errorMSg);
            LOGGER.debug(pwmRequest, errorInformation);
            pwmRequest.respondWithError(errorInformation);
        }
        return ProcessStatus.Halt;
    }

    @ActionHandler(action = "health")
    public ProcessStatus restHealthProcessor(final PwmRequest pwmRequest)
            throws IOException, ServletException, PwmUnrecoverableException
    {
        if (pwmRequest.getPwmApplication().getApplicationMode() == PwmApplicationMode.RUNNING) {

            if (!pwmRequest.isAuthenticated()) {
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_AUTHENTICATION_REQUIRED);
                LOGGER.debug(pwmRequest, errorInformation);
                pwmRequest.respondWithError(errorInformation);
                return ProcessStatus.Halt;
            }

            if (!pwmRequest.getPwmSession().getSessionManager().checkPermission(pwmRequest.getPwmApplication(), Permission.PWMADMIN)) {
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNAUTHORIZED, "admin privileges required");
                LOGGER.debug(pwmRequest, errorInformation);
                pwmRequest.respondWithError(errorInformation);
                return ProcessStatus.Halt;
            }
        }

        try {
            final HealthData jsonOutput = RestHealthServer.processGetHealthCheckData(
                    pwmRequest.getPwmApplication(),
                    pwmRequest.getLocale(),
                    false);
            final RestResultBean restResultBean = RestResultBean.withData(jsonOutput);
            pwmRequest.outputJsonResult(restResultBean);
        } catch (PwmException e) {
            final ErrorInformation errorInformation = e.getErrorInformation();
            LOGGER.debug(pwmRequest, errorInformation);
            pwmRequest.respondWithError(errorInformation);
        } catch (Exception e) {
            final String errorMessage = "unexpected error executing web service: " + e.getMessage();
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNKNOWN, errorMessage);
            LOGGER.debug(pwmRequest, errorInformation);
            pwmRequest.respondWithError(errorInformation);
        }
        return ProcessStatus.Halt;
    }

    @ActionHandler(action = "ping")
    public ProcessStatus processPingRequest(final PwmRequest pwmRequest)
            throws IOException
    {
        final PingResponse pingResponse = new PingResponse();
        pingResponse.setTime(Instant.now());
        pingResponse.setRuntimeNonce(pwmRequest.getPwmApplication().getRuntimeNonce());
        pwmRequest.outputJsonResult(RestResultBean.withData(pingResponse));
        return ProcessStatus.Halt;
    }

    public static String makeClientEtag(final PwmRequest pwmRequest)
            throws PwmUnrecoverableException
    {
        return makeClientEtag(pwmRequest.getPwmApplication(), pwmRequest.getPwmSession(), pwmRequest.getHttpServletRequest());
    }

    public static String makeClientEtag(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final HttpServletRequest httpServletRequest
    )
            throws PwmUnrecoverableException
    {
        final StringBuilder inputString = new StringBuilder();
        inputString.append(PwmConstants.BUILD_NUMBER);
        inputString.append(pwmApplication.getStartupTime().toEpochMilli());
        inputString.append(httpServletRequest.getSession().getMaxInactiveInterval());
        inputString.append(pwmApplication.getRuntimeNonce());

        if (pwmSession.getSessionStateBean().getLocale() != null) {
            inputString.append(pwmSession.getSessionStateBean().getLocale());
        }

        inputString.append(pwmSession.getSessionStateBean().getSessionID());
        if (pwmSession.isAuthenticated()) {
            inputString.append(pwmSession.getUserInfo().getUserGuid());
            inputString.append(pwmSession.getLoginInfoBean().getAuthTime());
        }

        return SecureEngine.hash(inputString.toString(), PwmHashAlgorithm.SHA1).toLowerCase();
    }

    private AppData makeAppData(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final HttpServletRequest request,
            final HttpServletResponse response,
            final String pageUrl
    )
            throws ChaiUnavailableException, PwmUnrecoverableException
    {
        final AppData appData = new AppData();
        appData.PWM_GLOBAL = makeClientData(pwmApplication, pwmSession, request, response, pageUrl);
        return appData;
    }

    private static Map<String,Object> makeClientData(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final HttpServletRequest request,
            final HttpServletResponse response,
            final String pageUrl
    )
            throws ChaiUnavailableException, PwmUnrecoverableException
    {
        final Locale userLocale = pwmSession.getSessionStateBean().getLocale();

        final Configuration config = pwmApplication.getConfig();
        final TreeMap<String,Object> settingMap = new TreeMap<>();

        settingMap.put("client.ajaxTypingTimeout", Integer.parseInt(config.readAppProperty(AppProperty.CLIENT_AJAX_TYPING_TIMEOUT)));
        settingMap.put("client.ajaxTypingWait", Integer.parseInt(config.readAppProperty(AppProperty.CLIENT_AJAX_TYPING_WAIT)));
        settingMap.put("client.activityMaxEpsRate", Integer.parseInt(config.readAppProperty(AppProperty.CLIENT_ACTIVITY_MAX_EPS_RATE)));
        settingMap.put("client.js.enableHtml5Dialog", Boolean.parseBoolean(config.readAppProperty(AppProperty.CLIENT_JS_ENABLE_HTML5DIALOG)));
        settingMap.put("client.locale", LocaleHelper.getBrowserLocaleString(pwmSession.getSessionStateBean().getLocale()) );
        settingMap.put("client.pwShowRevertTimeout", Integer.parseInt(config.readAppProperty(AppProperty.CLIENT_PW_SHOW_REVERT_TIMEOUT)));
        settingMap.put("enableIdleTimeout", config.readSettingAsBoolean(PwmSetting.DISPLAY_IDLE_TIMEOUT));
        settingMap.put("pageLeaveNotice", config.readSettingAsLong(PwmSetting.SECURITY_PAGE_LEAVE_NOTICE_TIMEOUT));
        settingMap.put("setting-showHidePasswordFields",pwmApplication.getConfig().readSettingAsBoolean(password.pwm.config.PwmSetting.DISPLAY_SHOW_HIDE_PASSWORD_FIELDS));
        settingMap.put("setting-displayEula",PwmConstants.ENABLE_EULA_DISPLAY);
        settingMap.put("setting-showStrengthMeter",config.readSettingAsBoolean(PwmSetting.PASSWORD_SHOW_STRENGTH_METER));

        {
            long idleSeconds = config.readSettingAsLong(PwmSetting.IDLE_TIMEOUT_SECONDS);
            if (pageUrl == null || pageUrl.isEmpty()) {
                LOGGER.warn(pwmSession, "request to /client data did not include pageUrl");
            } else {
                try {
                    final PwmURL pwmURL = new PwmURL(new URI(pageUrl), request.getContextPath());
                    final TimeDuration maxIdleTime = IdleTimeoutCalculator.idleTimeoutForRequest(pwmURL, pwmApplication, pwmSession);
                    idleSeconds = maxIdleTime.getTotalSeconds();
                } catch (Exception e) {
                    LOGGER.error(pwmSession, "error determining idle timeout time for request: " + e.getMessage());
                }
            }
            settingMap.put("MaxInactiveInterval", idleSeconds);
        }
        settingMap.put("paramName.locale", config.readAppProperty(AppProperty.HTTP_PARAM_NAME_LOCALE));
        settingMap.put("runtimeNonce",pwmApplication.getRuntimeNonce());
        settingMap.put("applicationMode",pwmApplication.getApplicationMode());

        final String contextPath = request.getContextPath();
        settingMap.put("url-context", contextPath);
        settingMap.put("url-logout", contextPath + PwmServletDefinition.Logout.servletUrl());
        settingMap.put("url-command", contextPath + PwmServletDefinition.PublicCommand.servletUrl());
        settingMap.put("url-resources", contextPath + "/public/resources" + pwmApplication.getResourceServletService().getResourceNonce());
        settingMap.put("url-restservice", contextPath + "/public/rest");

        {
            String passwordGuideText = pwmApplication.getConfig().readSettingAsLocalizedString(PwmSetting.DISPLAY_PASSWORD_GUIDE_TEXT,pwmSession.getSessionStateBean().getLocale());
            final MacroMachine macroMachine = pwmSession.getSessionManager().getMacroMachine(pwmApplication);
            passwordGuideText = macroMachine.expandMacros(passwordGuideText);
            settingMap.put("passwordGuideText",passwordGuideText);
        }


        {
            final List<String> formTypeOptions = new ArrayList<>();
            for (final FormConfiguration.Type type : FormConfiguration.Type.values()) {
                formTypeOptions.add(type.toString());
            }
            settingMap.put("formTypeOptions",formTypeOptions);
        }

        {
            final List<String> actionTypeOptions = new ArrayList<>();
            for (final ActionConfiguration.Type type : ActionConfiguration.Type.values()) {
                actionTypeOptions.add(type.toString());
            }
            settingMap.put("actionTypeOptions",actionTypeOptions);
        }

        {
            final List<String> epsTypes = new ArrayList<>();
            for (final EpsStatistic loopEpsType : EpsStatistic.values()) {
                epsTypes.add(loopEpsType.toString());
            }
            settingMap.put("epsTypes",epsTypes);
        }

        {
            final List<String> epsDurations = new ArrayList<>();
            for (final Statistic.EpsDuration loopEpsDuration : Statistic.EpsDuration.values()) {
                epsDurations.add(loopEpsDuration.toString());
            }
            settingMap.put("epsDurations",epsDurations);
        }

        {
            final Map<String,String> localeInfo = new LinkedHashMap<>();
            final Map<String,String> localeDisplayNames = new LinkedHashMap<>();
            final Map<String,String> localeFlags = new LinkedHashMap<>();

            final List<Locale> knownLocales = new ArrayList<>(pwmApplication.getConfig().getKnownLocales());
            knownLocales.sort(LocaleHelper.localeComparator( PwmConstants.DEFAULT_LOCALE));

            for (final Locale locale : knownLocales ) {
                final String flagCode = pwmApplication.getConfig().getKnownLocaleFlagMap().get(locale);
                localeFlags.put(locale.toString(),flagCode);
                localeInfo.put(locale.toString(),locale.getDisplayName( PwmConstants.DEFAULT_LOCALE) + " - " + locale.getDisplayLanguage(userLocale));
                localeDisplayNames.put(locale.toString(),locale.getDisplayLanguage());
            }

            settingMap.put("localeInfo",localeInfo);
            settingMap.put("localeDisplayNames",localeDisplayNames);
            settingMap.put("localeFlags",localeFlags);
            settingMap.put("defaultLocale",PwmConstants.DEFAULT_LOCALE.toString());
        }

        if (pwmApplication.getConfig().readSettingAsEnum(PwmSetting.LDAP_SELECTABLE_CONTEXT_MODE, SelectableContextMode.class) != SelectableContextMode.NONE) {
            final Map<String,Map<String,String>> ldapProfiles = new LinkedHashMap<>();
            for (final String ldapProfile : pwmApplication.getConfig().getLdapProfiles().keySet()) {
                final Map<String,String> contexts = pwmApplication.getConfig().getLdapProfiles().get(ldapProfile).getSelectableContexts(pwmApplication);
                ldapProfiles.put(ldapProfile,contexts);
            }
            settingMap.put("ldapProfiles",ldapProfiles);
        }

        return settingMap;
    }



    private Map<String,String> makeDisplayData(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final String bundleName
    )
    {
        Class displayClass = LocaleHelper.classForShortName(bundleName);
        displayClass = displayClass == null ? Display.class : displayClass;

        final Locale userLocale = pwmSession.getSessionStateBean().getLocale();
        final Configuration config = pwmApplication.getConfig();
        final TreeMap<String,String> displayStrings = new TreeMap<>();
        final ResourceBundle bundle = ResourceBundle.getBundle(displayClass.getName());
        try {
            final MacroMachine macroMachine = pwmSession.getSessionManager().getMacroMachine(pwmApplication);
            for (final String key : new TreeSet<>(Collections.list(bundle.getKeys()))) {
                String displayValue = LocaleHelper.getLocalizedMessage(userLocale, key, config, displayClass);
                displayValue = macroMachine.expandMacros(displayValue);
                displayStrings.put(key, displayValue);
            }
        } catch (Exception e) {
            LOGGER.error(pwmSession,"error expanding macro display value: " + e.getMessage());
        }
        return displayStrings;
    }

}
