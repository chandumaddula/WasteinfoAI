"""initial_schema

Revision ID: 6f0d97cd0f18
Revises: 
Create Date: 2026-10-11 09:02:12.548760+00:00
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = '6f0d97cd0f18'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Institutions table
    op.create_table(
        'institutions',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('name', sa.String(length=128), nullable=False),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('is_active', sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('id', name=op.f('pk_institutions')),
    )

    # 2. Users table
    op.create_table(
        'users',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('email', sa.String(length=255), nullable=False),
        sa.Column('password_hash', sa.String(length=255), nullable=True),
        sa.Column('role', sa.String(length=20), nullable=False),
        sa.Column('institution_id', sa.String(length=36), nullable=True),
        sa.Column('full_name', sa.String(length=128), nullable=True),
        sa.Column('display_name', sa.String(length=64), nullable=True),
        sa.Column('is_active', sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['institution_id'], ['institutions.id'], name=op.f('fk_users_institution_id_institutions')),
        sa.PrimaryKeyConstraint('id', name=op.f('pk_users')),
        sa.UniqueConstraint('email', name=op.f('uq_users_email')),
    )
    op.create_index(op.f('ix_users_created_at'), 'users', ['created_at'], unique=False)
    op.create_index(op.f('ix_users_email'), 'users', ['email'], unique=False)
    op.create_index(op.f('ix_users_institution_id'), 'users', ['institution_id'], unique=False)

    # 3. User tokens table
    op.create_table(
        'user_tokens',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('user_id', sa.String(length=36), nullable=False),
        sa.Column('token', sa.String(length=36), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('is_active', sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('last_used_at', sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], name=op.f('fk_user_tokens_user_id_users')),
        sa.PrimaryKeyConstraint('id', name=op.f('pk_user_tokens')),
        sa.UniqueConstraint('token', name=op.f('uq_user_tokens_token')),
        sa.UniqueConstraint('user_id', 'token', name='uq_user_tokens_user_id_token'),
    )
    op.create_index(op.f('ix_user_tokens_token'), 'user_tokens', ['token'], unique=False)
    op.create_index(op.f('ix_user_tokens_user_id'), 'user_tokens', ['user_id'], unique=False)

    # 4. Reports table
    op.create_table(
        'reports',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('client_uuid', sa.String(length=36), nullable=True),
        sa.Column('owner_id', sa.String(length=36), nullable=False),
        sa.Column('waste_category', sa.String(length=50), nullable=False),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('latitude', sa.Float(), nullable=True),
        sa.Column('longitude', sa.Float(), nullable=True),
        sa.Column('address_line', sa.Text(), nullable=True),
        sa.Column('city', sa.String(length=128), nullable=True),
        sa.Column('region', sa.String(length=128), nullable=True),
        sa.Column('country', sa.String(length=128), nullable=True),
        sa.Column('image_reference', sa.String(length=512), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('status', sa.String(length=20), nullable=False),
        sa.Column('version', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('sync_state', sa.String(length=20), nullable=True),
        sa.Column('sync_received_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('sync_last_attempt_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('sync_retry_count', sa.Integer(), nullable=False, server_default='0'),
        sa.ForeignKeyConstraint(['owner_id'], ['users.id'], name=op.f('fk_reports_owner_id_users')),
        sa.PrimaryKeyConstraint('id', name=op.f('pk_reports')),
        sa.UniqueConstraint('owner_id', 'client_uuid', name='uq_reports_owner_id_client_uuid'),
    )
    op.create_index(op.f('ix_reports_client_uuid'), 'reports', ['client_uuid'], unique=False)
    op.create_index(op.f('ix_reports_owner_id'), 'reports', ['owner_id'], unique=False)
    op.create_index(op.f('ix_reports_status'), 'reports', ['status'], unique=False)
    op.create_index(op.f('ix_reports_waste_category'), 'reports', ['waste_category'], unique=False)

    # 5. Audit events table
    op.create_table(
        'audit_events',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('subject_type', sa.String(length=50), nullable=False),
        sa.Column('subject_id', sa.String(length=36), nullable=False),
        sa.Column('event_type', sa.String(length=50), nullable=False),
        sa.Column('actor_id', sa.String(length=36), nullable=False),
        sa.Column('payload', sa.JSON(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('id', name=op.f('pk_audit_events')),
    )
    op.create_index(op.f('ix_audit_events_actor_id'), 'audit_events', ['actor_id'], unique=False)
    op.create_index(op.f('ix_audit_events_created_at'), 'audit_events', ['created_at'], unique=False)
    op.create_index(op.f('ix_audit_events_event_type'), 'audit_events', ['event_type'], unique=False)
    op.create_index(op.f('ix_audit_events_subject_id'), 'audit_events', ['subject_id'], unique=False)
    op.create_index(op.f('ix_audit_events_subject_type'), 'audit_events', ['subject_type'], unique=False)


def downgrade() -> None:
    op.drop_index(op.f('ix_audit_events_subject_type'), table_name='audit_events')
    op.drop_index(op.f('ix_audit_events_subject_id'), table_name='audit_events')
    op.drop_index(op.f('ix_audit_events_event_type'), table_name='audit_events')
    op.drop_index(op.f('ix_audit_events_created_at'), table_name='audit_events')
    op.drop_index(op.f('ix_audit_events_actor_id'), table_name='audit_events')
    op.drop_table('audit_events')

    op.drop_index(op.f('ix_reports_waste_category'), table_name='reports')
    op.drop_index(op.f('ix_reports_status'), table_name='reports')
    op.drop_index(op.f('ix_reports_owner_id'), table_name='reports')
    op.drop_index(op.f('ix_reports_client_uuid'), table_name='reports')
    op.drop_table('reports')

    op.drop_index(op.f('ix_user_tokens_user_id'), table_name='user_tokens')
    op.drop_index(op.f('ix_user_tokens_token'), table_name='user_tokens')
    op.drop_table('user_tokens')

    op.drop_index(op.f('ix_users_institution_id'), table_name='users')
    op.drop_index(op.f('ix_users_email'), table_name='users')
    op.drop_index(op.f('ix_users_created_at'), table_name='users')
    op.drop_table('users')

    op.drop_table('institutions')
