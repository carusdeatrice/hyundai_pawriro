package pawriro.backend;

final class Schema {
    static final String SQL = """
        CREATE EXTENSION IF NOT EXISTS postgis;
        
        CREATE TABLE IF NOT EXISTS reports (
            id uuid PRIMARY KEY,
            kind text NOT NULL,
            name text NOT NULL,
            coat text NOT NULL,
            traits text NOT NULL,
            missing_at timestamptz NOT NULL,
            photo_url text,
            last_seen geometry(Point,4326) NOT NULL,
            walk_route geometry(LineString,4326),
            status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','found','closed'))
        );
        CREATE TABLE IF NOT EXISTS search_areas (
            id uuid PRIMARY KEY,
            report_id uuid NOT NULL REFERENCES reports(id),
            name text NOT NULL,
            polygon geometry(Polygon,4326),
            center geography(Point,4326),
            radius_m integer,
            CONSTRAINT area_shape CHECK ((polygon IS NOT NULL AND center IS NULL AND radius_m IS NULL)
                OR (polygon IS NULL AND center IS NOT NULL AND radius_m IS NOT NULL AND radius_m BETWEEN 1 AND 5000)),
            CHECK (polygon IS NULL OR (ST_IsValid(polygon) AND NOT ST_IsEmpty(polygon)))
        );
        CREATE INDEX IF NOT EXISTS area_polygon_gist ON search_areas USING gist ((polygon::geography));
        CREATE INDEX IF NOT EXISTS area_center_gist ON search_areas USING gist (center);
        
        CREATE TABLE IF NOT EXISTS sightings (
            id uuid PRIMARY KEY,
            report_id uuid NOT NULL REFERENCES reports(id),
            location geometry(Point,4326) NOT NULL,
            seen_at timestamptz NOT NULL,
            photo_url text
        );
        CREATE TABLE IF NOT EXISTS participations (
            id uuid PRIMARY KEY,
            area_id uuid NOT NULL REFERENCES search_areas(id),
            device_id uuid NOT NULL,
            mode text NOT NULL CHECK (mode IN ('solo','passenger')),
            started_at timestamptz NOT NULL DEFAULT now(),
            result text CHECK (result IN ('no_sighting','sighting')),
            sighting_id uuid REFERENCES sightings(id),
            submitted_at timestamptz,
            hold_reason text,
            CONSTRAINT participation_sighting_required CHECK ((result = 'sighting') IS NOT TRUE OR sighting_id IS NOT NULL)
        );
        CREATE TABLE IF NOT EXISTS visit_events (
            id uuid PRIMARY KEY,
            participation_id uuid NOT NULL REFERENCES participations(id),
            event text NOT NULL CHECK (event IN ('arrived','entered','passed','parked','door_open','exited','returned','unparked')),
            location geometry(Point,4326) NOT NULL,
            recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
        );
        CREATE INDEX IF NOT EXISTS visit_sequence ON visit_events (participation_id, recorded_at);
        CREATE TABLE IF NOT EXISTS points (
            participation_id uuid NOT NULL REFERENCES participations(id),
            device_id uuid NOT NULL,
            area_id uuid NOT NULL REFERENCES search_areas(id),
            day date NOT NULL DEFAULT ((now() AT TIME ZONE 'Asia/Seoul')::date),
            kind text NOT NULL CHECK (kind IN ('base','photo')),
            amount integer NOT NULL CHECK ((kind='base' AND amount=50) OR (kind='photo' AND amount=25)),
            PRIMARY KEY (participation_id,kind)
        );
        CREATE UNIQUE INDEX IF NOT EXISTS points_daily_area_base ON points(device_id,area_id,day) WHERE kind='base';
        CREATE INDEX IF NOT EXISTS points_daily_balance ON points(device_id,day);
        CREATE TABLE IF NOT EXISTS guardian_accounts (
            username text PRIMARY KEY, salt bytea NOT NULL, password_hash bytea NOT NULL
        );
        CREATE TABLE IF NOT EXISTS guardian_sessions (
            token_hash text PRIMARY KEY, username text NOT NULL REFERENCES guardian_accounts(username),
            expires_at timestamptz NOT NULL
        );
        ALTER TABLE reports ADD COLUMN IF NOT EXISTS owner_name text REFERENCES guardian_accounts(username);
        ALTER TABLE reports ADD COLUMN IF NOT EXISTS closed_at timestamptz;
        ALTER TABLE reports ADD COLUMN IF NOT EXISTS purged_at timestamptz;
        ALTER TABLE reports ADD COLUMN IF NOT EXISTS places jsonb NOT NULL DEFAULT '[]';
        ALTER TABLE reports ADD COLUMN IF NOT EXISTS photo_data text;
        ALTER TABLE reports ALTER COLUMN last_seen DROP NOT NULL;
        ALTER TABLE search_areas ADD COLUMN IF NOT EXISTS retired boolean NOT NULL DEFAULT false;
        ALTER TABLE search_areas DROP CONSTRAINT IF EXISTS search_areas_check;
        ALTER TABLE search_areas DROP CONSTRAINT IF EXISTS area_shape;
        ALTER TABLE search_areas ADD CONSTRAINT area_shape CHECK (
            (retired AND polygon IS NULL AND center IS NULL AND radius_m IS NULL)
            OR (polygon IS NOT NULL AND center IS NULL AND radius_m IS NULL)
            OR (polygon IS NULL AND center IS NOT NULL AND radius_m IS NOT NULL AND radius_m BETWEEN 1 AND 5000)
        );
        ALTER TABLE participations DROP CONSTRAINT IF EXISTS participations_check;
        ALTER TABLE participations DROP CONSTRAINT IF EXISTS participation_sighting_required;
        ALTER TABLE participations ADD COLUMN IF NOT EXISTS sighting_redacted boolean NOT NULL DEFAULT false;
        ALTER TABLE participations ADD CONSTRAINT participation_sighting_required CHECK (
            result IS DISTINCT FROM 'sighting' OR sighting_id IS NOT NULL OR sighting_redacted
        );
        CREATE INDEX IF NOT EXISTS reports_owner_idx ON reports(owner_name);
        CREATE TABLE IF NOT EXISTS verified_candidates (
            poi_id text NOT NULL, area text NOT NULL, mode text NOT NULL CHECK(mode IN ('solo','passenger')),
            latitude double precision NOT NULL, longitude double precision NOT NULL,
            exit_latitude double precision, exit_longitude double precision,
            expires_at timestamptz NOT NULL, PRIMARY KEY(poi_id,area,mode)
        );
        ALTER TABLE verified_candidates ADD COLUMN IF NOT EXISTS display_name text NOT NULL DEFAULT '';
        ALTER TABLE verified_candidates ADD COLUMN IF NOT EXISTS opening_hours jsonb;
        ALTER TABLE reports ADD COLUMN IF NOT EXISTS review_state text NOT NULL DEFAULT 'clear';
        ALTER TABLE sightings ADD COLUMN IF NOT EXISTS review_state text NOT NULL DEFAULT 'clear';
        """;

    private Schema() {}
}
