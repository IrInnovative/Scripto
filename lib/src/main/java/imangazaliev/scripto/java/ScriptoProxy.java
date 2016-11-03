package imangazaliev.scripto.java;

import android.webkit.JavascriptInterface;

import com.google.gson.JsonSyntaxException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;

import imangazaliev.scripto.Scripto;
import imangazaliev.scripto.ScriptoException;
import imangazaliev.scripto.utils.ScriptoAssetsJavaScriptReader;
import imangazaliev.scripto.utils.ScriptoLogUtils;
import imangazaliev.scripto.utils.ScriptoUtils;
import imangazaliev.scripto.utils.StringUtils;

public class ScriptoProxy implements InvocationHandler {

    private Scripto scripto;
    private String jsVariableName;
    private String proxyId;
    private HashMap<String, ScriptoFunctionCall> functionCalls;

    public ScriptoProxy(Scripto scripto, Class<?> scriptClass) {
        this.scripto = scripto;
        this.jsVariableName = getJsVariableName(scriptClass);

        functionCalls = new HashMap();
        proxyId = StringUtils.randomString(5);

        //добавляем себя как интерфейс для приема коллбеков от JS
        scripto.getWebView().addJavascriptInterface(this, proxyId);
    }

    private String getJsVariableName(Class<?> scriptClass) {
        return scriptClass.isAnnotationPresent(JsVariableName.class) ? scriptClass.getAnnotation(JsVariableName.class).value() : null;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object... args) throws Throwable {
        //перенаправляем стандартные вызовы методов класса Object к объекту
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        ScriptoFunction scriptoFunction = new ScriptoFunction(scripto, jsVariableName, method, args, proxyId);
        Class<?> returnType = ScriptoUtils.getCallResponseType(method);
        String callCode = StringUtils.randomNumericString(5);

        ScriptoFunctionCall scriptoFunctionCall = new ScriptoFunctionCall(scriptoFunction, returnType, callCode);
        functionCalls.put(callCode, scriptoFunctionCall);

        return scriptoFunctionCall;
    }

    @JavascriptInterface
    public void onCallbackResponse(final String callbackCode, final String responseString) {
        ScriptoUtils.runOnUi(new Runnable() {
            @Override
            public void run() {
                onCallbackResponseUi(callbackCode, responseString);
            }
        });
    }

    private void onCallbackResponseUi(String callbackCode, String responseString) {
        if (!functionCalls.containsKey(callbackCode)) {
            return;
        }

        ScriptoFunctionCall functionCall = functionCalls.remove(callbackCode);
        ScriptoResponseCallback callback = functionCall.getResponseCallback();

        if (callback == null) {
            return;
        }

        Class<?> responseType = functionCall.getResponseType();
        if (responseString == null || responseType.isAssignableFrom(Void.class)) {
            //если ответ не получен (null) или функция ничего не должна возвращать(Void), передаем null
            callback.onResponse(null);
        } else if (responseType.isAssignableFrom(RawResponse.class)) {
            //возвращаем ответ без конвертации
            callback.onResponse(new RawResponse(responseString));
        } else {
            try {
                Object response = scripto.getJavaConverter().toObject(responseString, responseType);
                callback.onResponse(response);
                ScriptoLogUtils.logMessage(String.format("Function '%s' call success", functionCall.getScriptoFunction().getJsFunction()));
            } catch (JsonSyntaxException e) {
                ScriptoException error =  new ScriptoException("Ошибка при конвертации JSON из JS", e);
                ScriptoLogUtils.logError(error);
                onError(functionCall, error);
            }
        }
    }

    @JavascriptInterface
    public void onCallbackError(final String callbackCode, final String message) {
        ScriptoUtils.runOnUi(new Runnable() {
            @Override
            public void run() {
                onCallbackErrorUi(callbackCode, message);
            }
        });
    }

    private void onCallbackErrorUi(String callbackCode, String message) {
        if (!functionCalls.containsKey(callbackCode)) {
            return;
        }

        ScriptoFunctionCall functionCall = functionCalls.remove(callbackCode);
        ScriptoLogUtils.logError(String.format("Function '%s' call error. Message: %s", functionCall.getScriptoFunction().getJsFunction(), message));
        onError(functionCall, new JavaScriptException(message));
    }

    private void onError(ScriptoFunctionCall functionCall, ScriptoException error) {
        ScriptoErrorCallback callback = functionCall.getErrorCallback();
        if (callback == null && functionCall.isThrowOnError()) {
            throw error;
        } else if (callback != null) {
            callback.onError(error);
        }
    }

}
