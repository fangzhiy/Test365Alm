-- R06-M09-001-FIX04.  A finished attempt is immutable not only through the
-- run_step table, but also through the event stream that records step writes.
-- Keep RUN_FINISHED in the finishing transaction legal; reject a later
-- STEP_RECORDED event once the attempt has reached the terminal state.

CREATE OR REPLACE FUNCTION execution_terminal_step_event_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.event_type = 'STEP_RECORDED'
       AND (
           EXISTS (
               SELECT 1 FROM run_attempt a
               WHERE a.tenant_id = NEW.tenant_id
                 AND a.project_id = NEW.project_id
                 AND a.run_id = NEW.run_id
                 AND a.id = NEW.attempt_id
                 AND a.status = 'FINISHED'
           )
           OR EXISTS (
               SELECT 1 FROM execution_run r
               WHERE r.tenant_id = NEW.tenant_id
                 AND r.project_id = NEW.project_id
                 AND r.id = NEW.run_id
                 AND r.status = 'FINISHED'
           )
       ) THEN
        RAISE EXCEPTION 'finished attempt cannot receive step result events'
            USING ERRCODE = '55006';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS execution_event_terminal_step_guard ON execution_event;
CREATE TRIGGER execution_event_terminal_step_guard
    BEFORE INSERT ON execution_event
    FOR EACH ROW EXECUTE FUNCTION execution_terminal_step_event_guard();

DROP TRIGGER IF EXISTS execution_outbox_terminal_step_guard ON execution_outbox_event;
CREATE TRIGGER execution_outbox_terminal_step_guard
    BEFORE INSERT ON execution_outbox_event
    FOR EACH ROW EXECUTE FUNCTION execution_terminal_step_event_guard();
