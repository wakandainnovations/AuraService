package com.aura.service.alert;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.repository.ManagedEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertDispatcherTest {

    private static final Long ENTITY_ID = 7L;
    private static final String ENTITY_NAME = "Galaxy Quest";

    private ManagedEntityRepository entityRepository;
    private RecordingEmailChannel emailChannel;
    private RecordingWebhookChannel webhookChannel;
    private AlertDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        emailChannel = new RecordingEmailChannel();
        webhookChannel = new RecordingWebhookChannel();
        dispatcher = new AlertDispatcher(emailChannel, webhookChannel, entityRepository);

        ManagedEntity e = new ManagedEntity();
        e.setId(ENTITY_ID);
        e.setName(ENTITY_NAME);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));
    }

    private SentimentAlert alert() {
        return SentimentAlert.builder()
                .id(99L)
                .managedEntityId(ENTITY_ID)
                .kind(SentimentAlert.Kind.SPIKE)
                .status(SentimentAlert.Status.OPEN)
                .currentValue(0.5)
                .baselineValue(0.2)
                .build();
    }

    @Test
    void dispatchesToBothChannelsWithEntityName() {
        SentimentAlert a = alert();

        dispatcher.dispatch(a);

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).entityName).isEqualTo(ENTITY_NAME);
        assertThat(emailChannel.calls.get(0).alert).isSameAs(a);
        assertThat(webhookChannel.calls).hasSize(1);
        assertThat(webhookChannel.calls.get(0).entityName).isEqualTo(ENTITY_NAME);
        assertThat(webhookChannel.calls.get(0).alert).isSameAs(a);
    }

    @Test
    void emailFailureDoesNotPreventWebhookDispatch() {
        emailChannel.throwOnSend = new RuntimeException("smtp down");

        dispatcher.dispatch(alert());

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(webhookChannel.calls).hasSize(1);
    }

    @Test
    void webhookFailureDoesNotPropagate() {
        webhookChannel.throwOnSend = new RuntimeException("network");

        dispatcher.dispatch(alert());

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(webhookChannel.calls).hasSize(1);
    }

    @Test
    void usesNullEntityNameWhenEntityMissing() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.empty());

        dispatcher.dispatch(alert());

        assertThat(emailChannel.calls.get(0).entityName).isNull();
        assertThat(webhookChannel.calls.get(0).entityName).isNull();
    }

    @Test
    void nullAlertIsNoOp() {
        dispatcher.dispatch(null);

        assertThat(emailChannel.calls).isEmpty();
        assertThat(webhookChannel.calls).isEmpty();
    }

    static class Call {
        final SentimentAlert alert;
        final String entityName;

        Call(SentimentAlert alert, String entityName) {
            this.alert = alert;
            this.entityName = entityName;
        }
    }

    static class RecordingEmailChannel implements EmailChannel {
        final List<Call> calls = new ArrayList<>();
        RuntimeException throwOnSend;

        @Override
        public void send(SentimentAlert alert, String entityName) {
            calls.add(new Call(alert, entityName));
            if (throwOnSend != null) {
                throw throwOnSend;
            }
        }
    }

    static class RecordingWebhookChannel extends WebhookChannel {
        final List<Call> calls = new ArrayList<>();
        RuntimeException throwOnSend;

        RecordingWebhookChannel() {
            super(null);
        }

        @Override
        public void send(SentimentAlert alert, String entityName) {
            calls.add(new Call(alert, entityName));
            if (throwOnSend != null) {
                throw throwOnSend;
            }
        }
    }
}
