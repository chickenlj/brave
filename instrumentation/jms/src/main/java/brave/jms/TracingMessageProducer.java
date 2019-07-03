/*
 * Copyright 2013-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.jms;

import brave.Span;
import brave.Tracer;
import brave.messaging.ProducerHandler;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import javax.jms.CompletionListener;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueSender;
import javax.jms.Topic;
import javax.jms.TopicPublisher;

import static brave.jms.JmsTracing.destination;
import static brave.jms.TracingConnection.TYPE_QUEUE;
import static brave.jms.TracingConnection.TYPE_TOPIC;

/** Implements all interfaces as according to ActiveMQ, this is typical of JMS 1.1. */
final class TracingMessageProducer implements QueueSender, TopicPublisher {
  static TracingMessageProducer create(MessageProducer delegate, JmsTracing jmsTracing) {
    if (delegate instanceof TracingMessageProducer) return (TracingMessageProducer) delegate;
    return new TracingMessageProducer(delegate, jmsTracing);
  }

  final MessageProducer delegate;
  final int types;
  final ProducerHandler<Destination, Message, Message> handler;
  final CurrentTraceContext current;
  final Tracer tracer;

  TracingMessageProducer(MessageProducer delegate, JmsTracing jmsTracing) {
    this.delegate = delegate;
    int types = 0;
    if (delegate instanceof QueueSender) types |= TYPE_QUEUE;
    if (delegate instanceof TopicPublisher) types |= TYPE_TOPIC;
    this.types = types;
    this.handler = jmsTracing.producerHandler;
    this.current = jmsTracing.messageTracing.tracing().currentTraceContext();
    this.tracer = jmsTracing.messageTracing.tracing().tracer();
  }

  @Override public void setDisableMessageID(boolean value) throws JMSException {
    delegate.setDisableMessageID(value);
  }

  @Override public boolean getDisableMessageID() throws JMSException {
    return delegate.getDisableMessageID();
  }

  @Override public void setDisableMessageTimestamp(boolean value) throws JMSException {
    delegate.setDisableMessageTimestamp(value);
  }

  @Override public boolean getDisableMessageTimestamp() throws JMSException {
    return delegate.getDisableMessageTimestamp();
  }

  @Override public void setDeliveryMode(int deliveryMode) throws JMSException {
    delegate.setDeliveryMode(deliveryMode);
  }

  @Override public int getDeliveryMode() throws JMSException {
    return delegate.getDeliveryMode();
  }

  @Override public void setPriority(int defaultPriority) throws JMSException {
    delegate.setPriority(defaultPriority);
  }

  @Override public int getPriority() throws JMSException {
    return delegate.getPriority();
  }

  @Override public void setTimeToLive(long timeToLive) throws JMSException {
    delegate.setTimeToLive(timeToLive);
  }

  @Override public long getTimeToLive() throws JMSException {
    return delegate.getTimeToLive();
  }

  /* @Override JMS 2.0 method: Intentionally no override to ensure JMS 1.1 works! */
  @JMS2_0 public void setDeliveryDelay(long deliveryDelay) throws JMSException {
    delegate.setDeliveryDelay(deliveryDelay);
  }

  /* @Override JMS 2.0 method: Intentionally no override to ensure JMS 1.1 works! */
  @JMS2_0 public long getDeliveryDelay() throws JMSException {
    return delegate.getDeliveryDelay();
  }

  @Override public Destination getDestination() throws JMSException {
    return delegate.getDestination();
  }

  @Override public void close() throws JMSException {
    delegate.close();
  }

  @Override public void send(Message message) throws JMSException {
    Destination destination = destination(message);

    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      delegate.send(message);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  @Override public void send(Message message, int deliveryMode, int priority, long timeToLive)
    throws JMSException {
    Destination destination = destination(message);

    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      delegate.send(message, deliveryMode, priority, timeToLive);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  enum SendDestination {
    DESTINATION {
      @Override void apply(MessageProducer producer, Destination destination, Message message)
        throws JMSException {
        producer.send(destination, message);
      }
    },
    QUEUE {
      @Override void apply(MessageProducer producer, Destination destination, Message message)
        throws JMSException {
        ((QueueSender) producer).send((Queue) destination, message);
      }
    },
    TOPIC {
      @Override void apply(MessageProducer producer, Destination destination, Message message)
        throws JMSException {
        ((TopicPublisher) producer).publish((Topic) destination, message);
      }
    };

    abstract void apply(MessageProducer producer, Destination destination, Message message)
      throws JMSException;
  }

  @Override public void send(Destination destination, Message message) throws JMSException {
    send(SendDestination.DESTINATION, destination, message);
  }

  void send(SendDestination sendDestination, Destination destination, Message message)
    throws JMSException {
    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      sendDestination.apply(delegate, destination, message);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  @Override
  public void send(Destination destination, Message message, int deliveryMode, int priority,
    long timeToLive) throws JMSException {
    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      delegate.send(destination, message, deliveryMode, priority, timeToLive);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  /* @Override JMS 2.0 method: Intentionally no override to ensure JMS 1.1 works! */
  @JMS2_0
  public void send(Message message, CompletionListener completionListener) throws JMSException {
    Destination destination = destination(message);
    Span span = handler.startSend(destination, message);
    completionListener = TracingCompletionListener.create(completionListener, span, current);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      delegate.send(message, completionListener);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      handler.finishSend(destination, message, span);
      throw e;
    } finally {
      ws.close();
    }
  }

  /* @Override JMS 2.0 method: Intentionally no override to ensure JMS 1.1 works! */
  @JMS2_0 public void send(Message message, int deliveryMode, int priority, long timeToLive,
      CompletionListener completionListener) throws JMSException {
    Destination destination = destination(message);
    Span span = handler.startSend(destination, message);
    completionListener = TracingCompletionListener.create(completionListener, span, current);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      delegate.send(message, deliveryMode, priority, timeToLive, completionListener);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      handler.finishSend(destination, message, span);
      throw e;
    } finally {
      ws.close();
    }
  }

  /* @Override JMS 2.0 method: Intentionally no override to ensure JMS 1.1 works! */
  @JMS2_0 public void send(Destination destination, Message message,
      CompletionListener completionListener) throws JMSException {
    Span span = handler.startSend(destination, message);
    completionListener = TracingCompletionListener.create(completionListener, span, current);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      delegate.send(destination, message, completionListener);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      handler.finishSend(destination, message, span);
      throw e;
    } finally {
      ws.close();
    }
  }

  /* @Override JMS 2.0 method: Intentionally no override to ensure JMS 1.1 works! */
  @JMS2_0 public void send(Destination destination, Message message, int deliveryMode, int priority,
      long timeToLive, CompletionListener completionListener) throws JMSException {
    Span span = handler.startSend(destination, message);
    completionListener = TracingCompletionListener.create(completionListener, span, current);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      delegate.send(destination, message, deliveryMode, priority, timeToLive, completionListener);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      handler.finishSend(destination, message, span);
      throw e;
    } finally {
      ws.close();
    }
  }

  // QueueSender

  @Override public Queue getQueue() throws JMSException {
    checkQueueSender();
    return ((QueueSender) delegate).getQueue();
  }

  @Override public void send(Queue queue, Message message) throws JMSException {
    checkQueueSender();
    send(SendDestination.QUEUE, queue, message);
  }

  @Override
  public void send(Queue queue, Message message, int deliveryMode, int priority, long timeToLive)
    throws JMSException {
    checkQueueSender();
    QueueSender qs = (QueueSender) delegate;

    Destination destination = destination(message);
    if (destination == null) destination = qs.getDestination();

    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      qs.send(queue, message, deliveryMode, priority, timeToLive);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  void checkQueueSender() {
    if ((types & TYPE_QUEUE) != TYPE_QUEUE) {
      throw new IllegalStateException(delegate + " is not a QueueSender");
    }
  }

  // TopicPublisher

  @Override public Topic getTopic() throws JMSException {
    checkTopicPublisher();
    return ((TopicPublisher) delegate).getTopic();
  }

  @Override public void publish(Message message) throws JMSException {
    checkTopicPublisher();
    TopicPublisher tp = (TopicPublisher) delegate;

    Destination destination = destination(message);
    if (destination == null) destination = tp.getDestination();

    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      tp.publish(message);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  @Override public void publish(Message message, int deliveryMode, int priority, long timeToLive)
    throws JMSException {
    checkTopicPublisher();
    TopicPublisher tp = (TopicPublisher) delegate;

    Destination destination = destination(message);
    if (destination == null) destination = tp.getDestination();

    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      tp.publish(message, deliveryMode, priority, timeToLive);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  @Override public void publish(Topic topic, Message message) throws JMSException {
    checkTopicPublisher();
    send(SendDestination.TOPIC, topic, message);
  }

  @Override
  public void publish(Topic topic, Message message, int deliveryMode, int priority, long timeToLive)
    throws JMSException {
    checkTopicPublisher();
    TopicPublisher tp = (TopicPublisher) delegate;

    Destination destination = destination(message);
    if (destination == null) destination = tp.getDestination();

    Span span = handler.startSend(destination, message);
    Scope ws = current.newScope(span.context()); // animal-sniffer mistakes this for AutoCloseable
    try {
      tp.publish(topic, message, deliveryMode, priority, timeToLive);
    } catch (RuntimeException | JMSException | Error e) {
      span.error(e);
      throw e;
    } finally {
      ws.close();
      handler.finishSend(destination, message, span);
    }
  }

  void checkTopicPublisher() {
    if ((types & TYPE_TOPIC) != TYPE_TOPIC) {
      throw new IllegalStateException(delegate + " is not a TopicPublisher");
    }
  }
}
