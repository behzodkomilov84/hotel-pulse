package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.agent.AgentService;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.faktura.FakturaAccount;
import behzoddev.hotelpulse.faktura.FakturaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Mehmonxona integratsiyalarining holati (Интеграциялар sahifasi va mehmonxona formasi uchun). */
@Component
@RequiredArgsConstructor
public class IntegrationStatus {

    private final AgentService agentService;
    private final FakturaService fakturaService;

    /** level: ok — ishlayapti, warn — xato, pending — tasdiq/birinchi sinxron kutilmoqda, off — ulanmagan. */
    public record State(String level, String text) {

        static final State OFF = new State("off", "Уланмаган");

        public boolean connected() {
            return !"off".equals(level);
        }
    }

    /** Mehmonxona × integratsiyalar. */
    public record Row(Hotel hotel, State exelyPms, State exelyConnect, State shelter, State onec, State esf) {

        public int connectedCount() {
            return (int) List.of(exelyPms, exelyConnect, shelter, onec, esf).stream().filter(State::connected).count();
        }
    }

    public Row row(Hotel h) {
        return row(h, agentService.agents());
    }

    public Row row(Hotel h, List<AgentService.Agent> agents) {
        State pms = h.hasPmsKey() ? syncState(h) : State.OFF;
        State connect = !h.usesConnectApi() ? State.OFF
                : h.hasPmsKey() ? new State("pending", "Захира (PMS устун)") : syncState(h);
        return new Row(h, pms, connect, agentState(h, agents, "SHELTER"), agentState(h, agents, "1C"), esfState(h.getId()));
    }

    private static State syncState(Hotel h) {
        if (h.getExelyLastSyncOk() == null) {
            return new State("pending", "Ҳали синхронланмаган");
        }
        return h.getExelyLastSyncOk() ? new State("ok", "Ишлаяпти") : new State("warn", "Хатолик");
    }

    private static State agentState(Hotel h, List<AgentService.Agent> agents, String kind) {
        List<AgentService.Agent> mine = agents.stream().filter(a -> h.getId().equals(a.hotelId()) && kind.equals(a.kind())).toList();
        if (mine.stream().anyMatch(AgentService.Agent::isApproved)) {
            if ("SHELTER".equals(kind) && Boolean.FALSE.equals(h.getExelyLastSyncOk())) {
                return new State("warn", "Хатолик");
            }
            LocalDateTime last = mine.stream().map(AgentService.Agent::lastSyncAt).filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo).orElse(null);
            return last == null ? new State("pending", "Биринчи юклаш кутилмоқда") : new State("ok", "Ишлаяпти");
        }
        return mine.isEmpty() ? State.OFF : new State("pending", "Тасдиқ кутилмоқда");
    }

    private State esfState(Long hotelId) {
        FakturaAccount a = fakturaService.account(hotelId).orElse(null);
        if (a == null) {
            return State.OFF;
        }
        if (a.getSyncOk() == null) {
            return new State("pending", a.getProviderName() + " · текширилмаган");
        }
        return a.getSyncOk() ? new State("ok", a.getProviderName()) : new State("warn", a.getProviderName() + " · хато");
    }
}
