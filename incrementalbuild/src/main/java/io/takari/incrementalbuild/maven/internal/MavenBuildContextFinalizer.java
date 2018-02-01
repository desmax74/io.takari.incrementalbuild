package io.takari.incrementalbuild.maven.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.scope.MojoExecutionScoped;
import org.apache.maven.execution.scope.WeakMojoExecutionListener;
import org.apache.maven.plugin.MojoExecutionException;

import io.takari.builder.internal.annotations.Nullable;
import io.takari.incrementalbuild.MessageSeverity;
import io.takari.incrementalbuild.spi.AbstractBuildContext;
import io.takari.incrementalbuild.spi.BuildContextFinalizer;
import io.takari.incrementalbuild.spi.Message;
import io.takari.incrementalbuild.spi.MessageSinkAdaptor;
import io.takari.incrementalbuild.workspace.MessageSink;


@Named
@MojoExecutionScoped
public class MavenBuildContextFinalizer
    implements
      WeakMojoExecutionListener,
      BuildContextFinalizer {

  @Inject
  @Nullable
  private MessageSink messageSink;

  private final List<AbstractBuildContext> contexts = new ArrayList<>();

  @Inject
  public MavenBuildContextFinalizer() {}

  public void registerContext(AbstractBuildContext context) {
    contexts.add(context);
  }

  protected List<AbstractBuildContext> getRegisteredContexts() {
    return contexts;
  }

  @Override
  public void afterMojoExecutionSuccess(MojoExecutionEvent event) throws MojoExecutionException {
    final Map<Object, Collection<Message>> messages = new HashMap<>();

    MessageSinkAdaptor messager = new MessageSinkAdaptor() {
      @Override
      public void record(Map<Object, Collection<Message>> allMessages,
          Map<Object, Collection<Message>> newMessages) {
        if (messageSink != null) {
          for (Map.Entry<Object, Collection<Message>> entry : newMessages.entrySet()) {
            Object resource = entry.getKey();
            for (Message message : entry.getValue()) {
              messageSink.message(resource, message.line, message.column, message.message,
                  toMessageSinkSeverity(message.severity), message.cause);
            }
          }
        }
        messages.putAll(allMessages);
      }

      @Override
      public void clear(Object resource) {
        if (messageSink != null) {
          messageSink.clearMessages(resource);
        }
      }

    };

    try {
      for (AbstractBuildContext context : contexts) {
        context.commit(messager);
      }

      if (messageSink == null) {
        failBuild(messages);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Could not maintain incremental build state", e);
    }
  }

  protected void failBuild(final Map<Object, Collection<Message>> messages)
      throws MojoExecutionException {
    // without messageSink, have to raise exception if there were errors
    int errorCount = 0;
    StringBuilder errors = new StringBuilder();
    for (Map.Entry<Object, Collection<Message>> entry : messages.entrySet()) {
      Object resource = entry.getKey();
      for (Message message : entry.getValue()) {
        if (message.severity == MessageSeverity.ERROR) {
          errorCount++;
          errors.append(String.format("%s:[%d:%d] %s\n", resource.toString(), message.line,
              message.column, message.message));
        }
      }
    }

    if (errorCount > 0 && isFailOnErrorPresent(contexts)) {
      throw new MojoExecutionException(errorCount + " error(s) encountered:\n" + errors.toString());
    }
  }

  private boolean isFailOnErrorPresent(List<AbstractBuildContext> contexts){
    boolean failOnError = true;
    for (AbstractBuildContext context : contexts) {
      if(!context.isFailOnError()){
        failOnError = false;
        break;
      }
    }
    return failOnError;
  }

  @Override
  public void beforeMojoExecution(MojoExecutionEvent event) throws MojoExecutionException {}

  @Override
  public void afterExecutionFailure(MojoExecutionEvent event) {}

  protected static MessageSink.Severity toMessageSinkSeverity(MessageSeverity severity) {
    switch (severity) {
      case ERROR:
        return MessageSink.Severity.ERROR;
      case WARNING:
        return MessageSink.Severity.WARNING;
      case INFO:
        return MessageSink.Severity.INFO;
      default:
        throw new IllegalArgumentException();
    }
  }

}
